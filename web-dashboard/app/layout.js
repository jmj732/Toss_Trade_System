import "./globals.css";

export const metadata = {
  title: "Trade · 미국주식 운영",
  description: "포트폴리오와 위험을 한눈에 확인하는 투자 운영 화면"
};

export default function RootLayout({ children }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
