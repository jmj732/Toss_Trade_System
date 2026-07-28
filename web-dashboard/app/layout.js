import "./globals.css";

export const metadata = {
  title: "Trade Control",
  description: "Session-backed portfolio dashboard"
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
