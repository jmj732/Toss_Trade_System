# OIDC Auth Session Delta

- Spring Security OAuth2 Client의 authorization-code OIDC 로그인만 사용한다.
- 공급자 설정은 `spring.security.oauth2.client` 환경 설정으로 주입한다.
- 외부 식별자는 `(issuer, subject)`로 구분하며 둘 다 비어 있지 않아야 한다.
- `users`에 nullable `oidc_issuer`, `oidc_subject`를 추가한다.
- 기존 내부 UUID 기반 사용자는 유지하고 두 OIDC 열은 함께 null 또는 함께 non-null이다.
- `(oidc_issuer, oidc_subject)`는 유일하며 동시 최초 로그인도 하나의 내부 UUID로 수렴한다.
- OIDC 인증 성공 시 DB 매핑 후 Principal 이름을 내부 UUID 문자열로 노출한다.
- 기존 API 컨트롤러의 UUID Principal 계약은 변경하지 않는다.
- 인증 정보는 서버 `HttpSession`에 저장하며 JWT를 발급하거나 수용하지 않는다.
- CSRF는 Spring Security 기본 보호를 유지한다.
- 로그인 성공 시 세션 ID를 변경해 session fixation을 차단한다.
- `POST /logout`은 CSRF를 요구하고 세션·인증·`JSESSIONID`를 제거한 뒤 204를 반환한다.
- API는 인증되지 않은 요청에 401을 반환한다.
- 비밀번호 로그인, form login, HTTP Basic, 관리자 권한은 추가하지 않는다.
- 테스트는 mock OIDC principal, PostgreSQL 매핑, CSRF, 로그아웃, 소유권 회귀를 검증한다.
