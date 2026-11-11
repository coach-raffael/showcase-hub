# Einrichtung des Showcase-Hubs per gh CLI – für PowerShell ISE (Windows PowerShell 5.1) und PowerShell 7.
#
# Voraussetzungen auf dem Rechner: gh (angemeldet), git, OpenSSH-Client (ssh, ssh-keygen, ssh-keyscan).
# ISE kann keine Eingaben an Konsolenprogramme weiterreichen: SSH zum VPS muss per Schlüssel ohne Passwort gehen,
# und gh braucht den Scope admin:org schon vorher (sonst einmal im normalen Terminal: gh auth refresh -s admin:org).
# Vorher von Hand: DNS-Eintrag A "demos" → VPS-IP bei Hostinger; Fine-grained Token für den Hub (Actions: write auf showcase-hub, Contents: write auf portfolio-page-raffael).
#
# Abschnittsweise ausführen (in ISE: markieren + F8) – oder alles mit F5. Jeder Schritt ist wiederholbar.

# Bewusst nicht "Stop": ISE wertet jede stderr-Zeile von ssh/git als Fehler. Geprüft wird mit Assert-Ok.
$ErrorActionPreference = "Continue"
function Assert-Ok([string]$what) { if ($LASTEXITCODE -ne 0) { throw "$what fehlgeschlagen (Exit $LASTEXITCODE)" } }

# ---------------------------------------------------------------------------------------------------------
# 0. Einstellungen
# ---------------------------------------------------------------------------------------------------------
$Org        = "coach-raffael"
$Repo       = "$Org/showcase-hub"
$Homepage   = "$Org/portfolio-page-raffael"
$Identity   = "$Org/identity"                # Keycloak – Clients demos-web/demos-mcp liegen dort als Code
$VpsHost    = "srv1366958.hstgr.cloud"
$VpsAdmin   = "root"                        # Benutzer mit Root-Rechten für die einmalige Einrichtung
$RootKey    = Join-Path $HOME ".ssh\hostinger_root"   # dein Schlüssel für root@VPS
$DeployUser = "demos-deploy"
$KeyFile    = Join-Path $env:USERPROFILE ".ssh\showcase-hub-deploy"
$Vault      = Join-Path $env:USERPROFILE "showcase-hub-secrets.txt"   # danach in den Passwortmanager, Datei löschen

# gh braucht für Org-Secrets den Scope admin:org
$ghStatus = (gh auth status 2>&1 | Out-String)
if ($ghStatus -notmatch "admin:org") {
    throw "gh fehlt der Scope admin:org – einmal im normalen Terminal: gh auth refresh -h github.com -s admin:org"
}
$Ssh = @("-o", "StrictHostKeyChecking=accept-new", "-o", "BatchMode=yes")
if (-not (Test-Path $RootKey)) { throw "Root-Schlüssel $RootKey nicht gefunden" }

# ---------------------------------------------------------------------------------------------------------
# 1. Zufallswerte erzeugen (ohne openssl)
# ---------------------------------------------------------------------------------------------------------
function New-RandomBytes([int]$n) {
    $b = New-Object byte[] $n
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($b); $rng.Dispose()
    return ,$b
}
function New-Hex([int]$n) { -join ((New-RandomBytes $n) | ForEach-Object { $_.ToString("x2") }) }

if (Test-Path $Vault) {
    # Wiederholter Lauf: vorhandene Werte weiterverwenden (HUB_SECRET_KEY darf sich nie ändern!)
    $v = @{}; Get-Content $Vault | Where-Object { $_ -match "^([A-Z_]+)=(.*)$" } | ForEach-Object { $v[$Matches[1]] = $Matches[2] }
    $DbPassword   = $v.DB_PASSWORD
    $AuthSecret   = $v.AUTH_SECRET
    $HubSecretKey = $v.HUB_SECRET_KEY
    $HubCiToken   = $v.HUB_CI_TOKEN
    $AgentKey     = $v.AGENT_KEY
    $DemosWebSecret = $v.DEMOS_WEB_CLIENT_SECRET
    $DemosMcpSecret = $v.DEMOS_MCP_CLIENT_SECRET
    Write-Host "Werte aus $Vault übernommen."
} else {
    $DbPassword   = New-Hex 24
    $AuthSecret   = New-Hex 32
    $HubSecretKey = [Convert]::ToBase64String((New-RandomBytes 32))
    $HubCiToken   = "hubci_" + (New-Hex 24)
    $AgentKey     = New-Hex 32
    @(
        "# Showcase-Hub – in den Passwortmanager übernehmen, dann diese Datei löschen",
        "DB_PASSWORD=$DbPassword",
        "AUTH_SECRET=$AuthSecret",
        "HUB_SECRET_KEY=$HubSecretKey",
        "HUB_CI_TOKEN=$HubCiToken",
        "AGENT_KEY=$AgentKey"
    ) | Set-Content -Path $Vault -Encoding ASCII
    Write-Host "Neue Werte erzeugt und in $Vault gesichert."
}
# Keycloak-Client-Secrets (auch für ältere Vault-Dateien ohne diese Zeilen)
if (-not $DemosWebSecret) { $DemosWebSecret = New-Hex 32; Add-Content $Vault "DEMOS_WEB_CLIENT_SECRET=$DemosWebSecret" -Encoding ASCII }
if (-not $DemosMcpSecret) { $DemosMcpSecret = New-Hex 32; Add-Content $Vault "DEMOS_MCP_CLIENT_SECRET=$DemosMcpSecret" -Encoding ASCII }

# ---------------------------------------------------------------------------------------------------------
# 2. Deploy-Schlüssel (SSH) für GitHub Actions → Benutzer demos-deploy auf dem VPS
# ---------------------------------------------------------------------------------------------------------
if (-not (Test-Path $KeyFile)) {
    # Leere Passphrase: Windows PowerShell 5.1 (ISE) braucht '""', PowerShell 7.3+ übergibt "" korrekt
    if ($PSVersionTable.PSVersion.Major -ge 7) { ssh-keygen -q -t ed25519 -C "showcase-hub-deploy" -f $KeyFile -N "" }
    else { ssh-keygen -q -t ed25519 -C "showcase-hub-deploy" -f $KeyFile -N '""' }
    Assert-Ok "ssh-keygen"
}
$DeployPub   = (Get-Content "$KeyFile.pub" -Raw).Trim()
# Host-Keys direkt vom Server lesen (über deinen root-Zugang) – ssh-keyscan liefert unter Windows oft nichts
$hk = ssh @Ssh -i $RootKey -o IdentitiesOnly=yes "$VpsAdmin@$VpsHost" "cat /etc/ssh/ssh_host_*_key.pub"
Assert-Ok "root-Anmeldung am VPS (Schlüssel $RootKey)"
# Zeilen "typ schlüssel kommentar" → known_hosts-Format "host typ schlüssel"
$KnownHosts = (($hk | Out-String) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match "^ssh-|^ecdsa-" } |
    ForEach-Object { $p = $_ -split "\s+"; "$VpsHost $($p[0]) $($p[1])" }) -join "`n"
if (-not $KnownHosts) { throw "Host-Keys nicht lesbar: $hk" }
Write-Host "Host-Keys:"; Write-Host $KnownHosts

# ---------------------------------------------------------------------------------------------------------
# 3. Server einrichten (einmalig): demo-agent, Benutzer demos-deploy, Verzeichnisse
# ---------------------------------------------------------------------------------------------------------
$remote = @"
set -e
rm -rf /tmp/showcase-hub && git clone -q --depth 1 https://github.com/$Repo /tmp/showcase-hub
AGENT_KEY='$AgentKey' DEPLOY_USER='$DeployUser' /tmp/showcase-hub/agent/install.sh
install -d -m 700 -o $DeployUser -g $DeployUser /home/$DeployUser/.ssh
grep -qxF '$DeployPub' /home/$DeployUser/.ssh/authorized_keys 2>/dev/null || echo '$DeployPub' >> /home/$DeployUser/.ssh/authorized_keys
chown $DeployUser`:$DeployUser /home/$DeployUser/.ssh/authorized_keys && chmod 600 /home/$DeployUser/.ssh/authorized_keys
mkdir -p /srv/showcase-hub/data /srv/showcase-hub/backups /opt/showcase-hub
chown $DeployUser /opt/showcase-hub
chown 10001 /srv/showcase-hub/data
exit 0
"@
# CRLF aus ISE entfernen, sonst stolpert bash
$remote = $remote -replace "`r", ""
$remote | ssh @Ssh -i $RootKey -o IdentitiesOnly=yes "$VpsAdmin@$VpsHost" "bash -s"
Assert-Ok "Server-Einrichtung"

$AgentGid = (ssh @Ssh -i $RootKey -o IdentitiesOnly=yes "$VpsAdmin@$VpsHost" "getent group demo-agent | cut -d: -f3" | Out-String).Trim()
if ($AgentGid -notmatch "^\d+$") { throw "AGENT_GID nicht lesbar: $AgentGid" }
Write-Host "AGENT_GID = $AgentGid"

# Probe: kommt der Deploy-Benutzer mit dem neuen Schlüssel rein und darf Docker?
ssh @Ssh -i $KeyFile -o IdentitiesOnly=yes "$DeployUser@$VpsHost" "docker ps --format '{{.Names}}' | head -3; systemctl is-active demo-agent"
Assert-Ok "Anmeldung als $DeployUser"

# ---------------------------------------------------------------------------------------------------------
# 4. Werte, die nur du kennst
# ---------------------------------------------------------------------------------------------------------
function Read-Secret([string]$prompt) {
    $s = Read-Host -Prompt $prompt -AsSecureString
    $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}
$HubGithubToken = Read-Secret "Fine-grained Token (Actions write: showcase-hub, Contents write: portfolio-page-raffael)"

# ---------------------------------------------------------------------------------------------------------
# 4b. Keycloak (Repo identity): Client-Secrets setzen und Realm ausrollen
# ---------------------------------------------------------------------------------------------------------
gh secret set DEMOS_WEB_CLIENT_SECRET --repo $Identity --env production --body $DemosWebSecret
Assert-Ok "identity: DEMOS_WEB_CLIENT_SECRET"
gh secret set DEMOS_MCP_CLIENT_SECRET --repo $Identity --env production --body $DemosMcpSecret
Assert-Ok "identity: DEMOS_MCP_CLIENT_SECRET"
gh workflow run deploy.yml --repo $Identity
Assert-Ok "identity-Deploy starten"
Start-Sleep -Seconds 8
$IdRun = gh run list --repo $Identity --workflow deploy.yml --event workflow_dispatch --limit 1 --json databaseId --jq ".[0].databaseId"
gh run watch $IdRun --repo $Identity --exit-status
Assert-Ok "identity-Deploy"
Write-Host "Keycloak: Clients demos-web/demos-mcp und Rolle demos-admin sind angelegt."
Write-Host "Rolle demos-admin dir selbst zuweisen: https://auth.coach-raffael.com/admin/ → coach-raffael → Users → (du) → Role mapping"
Write-Host "Für den Claude-Connector (demos-mcp) steht das Client-Secret in $Vault (DEMOS_MCP_CLIENT_SECRET)."

# ---------------------------------------------------------------------------------------------------------
# 5. GitHub: Environment, Secrets, Variablen
# ---------------------------------------------------------------------------------------------------------
gh api -X PUT "repos/$Repo/environments/production" | Out-Null

function Set-EnvSecret([string]$name, [string]$value) {
    if (-not $value) { throw "$name ist leer" }
    # --body statt Pipe: Windows PowerShell hängt beim Pipen an Konsolenprogramme einen Zeilenumbruch an
    gh secret set $name --repo $Repo --env production --body $value
    Assert-Ok "Secret $name"
}
Set-EnvSecret "VPS_HOST"               $VpsHost
Set-EnvSecret "VPS_SSH_KEY"            (Get-Content $KeyFile -Raw)
Set-EnvSecret "VPS_KNOWN_HOSTS"        $KnownHosts
Set-EnvSecret "DB_PASSWORD"            $DbPassword
Set-EnvSecret "AUTH_SECRET"            $AuthSecret
Set-EnvSecret "KEYCLOAK_CLIENT_SECRET" $DemosWebSecret
Set-EnvSecret "HUB_SECRET_KEY"         $HubSecretKey
Set-EnvSecret "HUB_CI_TOKEN"           $HubCiToken
Set-EnvSecret "HUB_GITHUB_TOKEN"       $HubGithubToken
Set-EnvSecret "AGENT_KEY"              $AgentKey

gh variable set AGENT_GID     --repo $Repo --body $AgentGid
gh variable set HOMEPAGE_REPO --repo $Repo --body $Homepage
gh variable set SSH_USER      --repo $Repo --body $DeployUser

# Org-Secret für alle Showcase-Repos (bei GitHub Free: nutzbar in öffentlichen Repos)
gh secret set SHOWCASE_HUB_TOKEN --org $Org --visibility all --body $HubCiToken
Assert-Ok "Org-Secret SHOWCASE_HUB_TOKEN"

gh secret list --repo $Repo --env production
gh variable list --repo $Repo

# ---------------------------------------------------------------------------------------------------------
# 6. Erster Deploy des Hubs (manuell), danach automatisch
# ---------------------------------------------------------------------------------------------------------
gh workflow run deploy.yml --repo $Repo -f image_tag=latest
Assert-Ok "deploy starten"
Start-Sleep -Seconds 8
$RunId = gh run list --repo $Repo --workflow deploy.yml --limit 1 --json databaseId --jq ".[0].databaseId"
gh run watch $RunId --repo $Repo --exit-status
Assert-Ok "deploy"

# Läuft? Dann künftig nach jedem grünen Build automatisch ausrollen
Invoke-RestMethod "https://demos.coach-raffael.com/api/health"
gh variable set DEPLOY_ENABLED --repo $Repo --body "true"

# ---------------------------------------------------------------------------------------------------------
# 7. Optional: Template-Repo für neue Showcases aus dem Ordner template/
# ---------------------------------------------------------------------------------------------------------
$Tmp = Join-Path $env:TEMP "demo-template"
if (Test-Path $Tmp) { Remove-Item $Tmp -Recurse -Force }
if (Test-Path (Join-Path $env:TEMP "showcase-hub-src")) { Remove-Item (Join-Path $env:TEMP "showcase-hub-src") -Recurse -Force }
git clone -q --depth 1 "https://github.com/$Repo" (Join-Path $env:TEMP "showcase-hub-src")
New-Item -ItemType Directory $Tmp | Out-Null
Copy-Item (Join-Path $env:TEMP "showcase-hub-src\template\*") $Tmp -Recurse -Force
Copy-Item (Join-Path $env:TEMP "showcase-hub-src\template\.github") $Tmp -Recurse -Force
Remove-Item (Join-Path $env:TEMP "showcase-hub-src") -Recurse -Force
Push-Location $Tmp
git init -q -b main
git add -A
git commit -q -m "Vorlage für Showcase-Repos (aus showcase-hub/template)"
gh repo create "$Org/demo-template" --public --source . --push --description "Template for showcase repos on demos.coach-raffael.com"
Assert-Ok "demo-template anlegen"
gh api -X PATCH "repos/$Org/demo-template" -F is_template=true | Out-Null
Pop-Location

Write-Host ""
Write-Host "Fertig. Offen: $Vault in den Passwortmanager übernehmen und löschen."
