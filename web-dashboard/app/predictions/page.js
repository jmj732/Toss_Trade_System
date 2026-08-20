import { redirect } from "next/navigation.js";

// 예측 관련 기능은 Settings 로 통합됐다. 기존 북마크(/predictions)는 보존하되 Settings 로 보낸다.
// 예측 기능의 도달 경로는 이제 /settings 한 곳뿐이다(중복 마운트 없음).
export default function PredictionsPage() {
  redirect("/settings");
}
