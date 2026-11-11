import Link from "next/link";

export default function NotFound() {
  return (
    <div className="grid min-h-dvh place-items-center p-4">
      <div className="max-w-md text-center">
        <p className="label mb-3">404</p>
        <h1 className="display text-[40px]">Nothing here.</h1>
        <p className="mt-4 text-muted">
          This demo does not exist or is not public.{" "}
          <Link className="underline hover:text-ink" href="/">
            See all showcases
          </Link>
        </p>
      </div>
    </div>
  );
}
