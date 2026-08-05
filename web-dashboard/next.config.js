const backend = process.env.BACKEND_URL ?? "http://localhost:8080";

if (!/^https?:\/\/[^/]+(?::\d+)?$/.test(backend)) {
  throw new Error("BACKEND_URL must be an HTTP(S) origin");
}

export default {
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${backend}/api/:path*` },
      { source: "/oauth2/:path*", destination: `${backend}/oauth2/:path*` },
      { source: "/login/oauth2/:path*", destination: `${backend}/login/oauth2/:path*` }
    ];
  }
};
