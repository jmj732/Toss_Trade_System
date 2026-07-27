# Broker Connection Credential Vault Design

## 1. 목표

다중 사용자 환경에서 토스증권 `client_id`와 `client_secret`을 애플리케이션이
암호화해 PostgreSQL에 저장하고, 기존 read-only `BrokerAdapter`가
`brokerConnectionId`로 안전하게 자격증명을 조회하도록 한다.

완료 조건:

- 연결의 소유 사용자가 아니면 존재 여부를 구분할 수 없는 `404`를 반환한다.
- 평문 자격증명은 요청 처리 중 일시적으로만 사용하며 DB, API 응답, 로그, 예외,
  영속 캐시에 저장하지 않는다.
- 활성 암호화 키가 없으면 Vault가 활성화되지 않으며, 저장된 키 버전을 찾지 못하거나
  GCM 인증에 실패하면 자격증명을 반환하지 않는다.
- 자격증명 교체·삭제 커밋 후 시작한 token 조회는 이전 revision cache를 사용하지
  않으며, 조회 중 revision 경쟁이 관찰되면 fail-closed한다.
- 생성·교체·삭제와 상태 변경은 트랜잭션 및 낙관적 락으로 경쟁을 제어한다.
- PostgreSQL Testcontainers 통합 테스트로 스키마, 권한, 암호화, 롤백, 동시성을 검증한다.

## 2. 범위

### 포함

- `BrokerConnection` 도메인, JPA 매핑, repository, Flyway V4
- 사용자 ID와 `brokerConnectionId` 소유권 연결
- 운영용 `TossCredentialProvider`
- JDK 표준 AES-256-GCM 기반 자격증명 암호화
- 설정 기반 키 버전 관리와 향후 재암호화가 가능한 데이터 구조
- 자격증명 생성, 교체, 논리 삭제, 토스 연결 검증 API
- 다른 사용자 접근 차단
- 평문 비밀값 비노출과 fail-closed 오류 처리
- PostgreSQL/Redis/MockMvc 통합 테스트

### 제외

- 실제 주문 제출
- 자동 포트폴리오 동기화
- 프론트엔드
- AWS KMS, Vault 등 외부 키 관리 서비스
- 사용자 가입·로그인 구현
- 기존 자격증명의 자동 일괄 재암호화 작업
- PostgreSQL RLS
- 물리 백업과 WAL에서 과거 ciphertext를 제거하는 crypto-erasure
- JVM 메모리의 평문 즉시 제거, zeroization, heap dump 비노출

이번 변경은 세션 인증을 새로 구현하지 않는다. API는 Spring Security의 인증된
`Principal` 이름을 UUID 사용자 ID로 사용하며, 인증 공급자가 없는 운영 구성에서는
요청을 허용하지 않는다.

## 3. 검토한 접근

### A. 애플리케이션 AES-GCM + 설정 기반 버전 키링 — 채택

- JDK `Cipher`, `SecureRandom`, `GCMParameterSpec`만 사용한다.
- DB는 ciphertext, nonce, key version만 저장한다.
- 새 쓰기는 활성 키를 사용하고 기존 행은 저장된 키 버전으로 복호화한다.
- 키를 DB와 분리할 수 있고 새 암호화 의존성이 필요 없다.

현재 범위와 배포 규모에 가장 작고 검증 가능한 해법이다.

### B. PostgreSQL `pgcrypto` — 제외

키 또는 평문을 SQL 세션으로 전달해야 하므로 애플리케이션과 DB의 비밀 경계가
불명확해진다. JPA 로그와 SQL 관측 경로의 노출 위험도 커진다.

### C. KMS 봉투 암호화 — 후속 단계

키 접근 감사, 폐기, 자동 회전에는 가장 적합하지만 이번 범위에서 키 관리 서비스
연동이 명시적으로 제외됐다. 현재 스키마의 `credential_key_version`은 이후 KEK
식별자로 전환할 수 있다.

## 4. 패키지와 책임

```text
com.jmj.trade.broker
  BrokerConnectionRef              brokerConnectionId만 보유

com.jmj.trade.broker.connection
  BrokerConnection                 encrypted state와 lifecycle invariant
  BrokerConnectionStatus           UNVERIFIED, ACTIVE, INVALID, DELETED
  BrokerConnectionRepository       package-private, user-scoped 조회
  BrokerConnectionService          생성·교체·삭제·검증 orchestration
  BrokerConnectionController       인증 사용자 REST 경계
  BrokerConnectionResponse         secret 없는 응답
  CredentialVaultProperties        enabled, active key version, versioned keys
  CredentialKeyring                시작 시 키 구성 검증
  CredentialCipher                 AES-GCM encrypt/decrypt
  EncryptedCredentials             ciphertext, nonce, key version
  DatabaseTossCredentialProvider   운영용 provider

com.jmj.trade.broker.toss
  TossCredentialMetadata           ciphertext 없는 current revision
  TossAccessToken                  token + credential revision
```

`BrokerConnection`은 평문을 필드로 보유하지 않는다. `CredentialCipher`는 단일 final
클래스이며 별도 암호화 interface/factory를 만들지 않는다.

`DatabaseTossCredentialProvider`는 내부 broker adapter용이다. 사용자 API는 이를
직접 호출하지 않고 항상 `BrokerConnectionService`의 소유권 검사를 통과한다.

## 5. 도메인 모델

```java
class BrokerConnection {
    UUID id;
    UUID userId;
    BrokerType brokerType;                 // TOSS_INVEST
    BrokerConnectionStatus status;
    byte[] credentialCiphertext;
    byte[] credentialNonce;
    Integer credentialKeyVersion;
    long credentialRevision;
    Instant lastValidatedAt;
    Instant createdAt;
    Instant updatedAt;
    Instant deletedAt;
    long version;                          // JPA @Version

    void replaceCredentials(EncryptedCredentials encrypted, Instant now);
    void markValidated(long expectedCredentialRevision, Instant now);
    void markInvalid(long expectedCredentialRevision, Instant now);
    void delete(Instant now);
}

record TossCredentialMetadata(long credentialRevision) {}

record TossAccessToken(String value, long credentialRevision) {
    @Override public String toString() { return "TossAccessToken[****]"; }
}
```

불변성:

- `UNVERIFIED`, `ACTIVE`, `INVALID`는 암호화 필드가 모두 존재하고 `deletedAt`은 없다.
- `DELETED`는 ciphertext, nonce, key version, `lastValidatedAt`을 제거하고
  `deletedAt`을 저장한다.
- 생성과 교체는 `UNVERIFIED` 상태다.
- 성공 검증만 `ACTIVE`, 토스 인증·인가 실패만 `INVALID`로 만든다.
- 네트워크, rate limit, 토스 5xx, 로컬 키/복호화 오류는 상태를 바꾸지 않는다.
- 교체와 삭제는 `credentialRevision`을 증가시킨다.
- 삭제된 연결은 복구하지 않는다. 재연결은 새 ID를 생성한다.

`credentialRevision`은 자격증명의 의미 버전이고 `version`은 DB 경쟁 제어용이다.
`credentialKeyVersion`은 암호화 키 선택용이므로 세 값을 합치지 않는다.

## 6. Flyway V4와 ERD 제약

현재 identity 모듈이 아직 없으므로 V4는 FK anchor만 제공하는 최소 `users` 테이블을
만든다. `User` JPA와 인증 API는 만들지 않는다.

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY
);

CREATE TABLE broker_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    broker_type VARCHAR(40) NOT NULL CHECK (broker_type = 'TOSS_INVEST'),
    status VARCHAR(30) NOT NULL CHECK (
        status IN ('UNVERIFIED', 'ACTIVE', 'INVALID', 'DELETED')
    ),
    credential_ciphertext BYTEA,
    credential_nonce BYTEA,
    credential_key_version INTEGER,
    credential_revision BIGINT NOT NULL CHECK (credential_revision > 0),
    last_validated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_broker_connection_secret_shape CHECK (
        (
            status <> 'DELETED'
            AND credential_ciphertext IS NOT NULL
            AND octet_length(credential_ciphertext) > 16
            AND credential_nonce IS NOT NULL
            AND octet_length(credential_nonce) = 12
            AND credential_key_version IS NOT NULL
            AND credential_key_version > 0
            AND deleted_at IS NULL
        )
        OR
        (
            status = 'DELETED'
            AND credential_ciphertext IS NULL
            AND credential_nonce IS NULL
            AND credential_key_version IS NULL
            AND last_validated_at IS NULL
            AND deleted_at IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_broker_connection_active_user_broker
    ON broker_connections(user_id, broker_type)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_broker_connection_owner
    ON broker_connections(user_id, id);
```

관계:

```text
USERS 1 ─── N BROKER_CONNECTIONS
```

Repository는 다음 형태만 제공한다.

```java
Optional<BrokerConnection> findByIdAndUserId(UUID id, UUID userId);
Optional<BrokerConnection> findByIdAndBrokerTypeAndStatusNot(
    UUID id, BrokerType brokerType, BrokerConnectionStatus status);
```

두 번째 메서드는 같은 `broker.connection` package의
`DatabaseTossCredentialProvider`만 사용한다. repository interface 자체를
package-private으로 두고 사용자 application service에 전역 `findById`를 노출하지
않는다.

provider의 metadata 조회는 ciphertext/nonce 컬럼을 entity로 적재하지 않는 projection
query로 `credentialRevision`만 읽는다. 실제 복호화 query는
`id + brokerType + non-deleted status + expectedCredentialRevision`을 모두 조건으로
사용한다.

## 7. 암호화 형식과 키 관리

### 키 설정

Vault는 다음 의미의 설정을 사용한다.

```text
broker.credentials.enabled
broker.credentials.active-key-version
broker.credentials.keys.<version>
```

키 값은 운영에서 Spring Boot config tree로 읽는 secret mount를 우선하고, 로컬
개발에서만 환경 변수를 허용한다. `application.yml`과 Git에는 넣지 않는다. 값은
Base64로 인코딩한 32-byte AES 키다.

- `enabled=false` 또는 미설정이면 운영 provider와 관리 API bean을 만들지 않는다.
- `enabled=true`인데 활성 버전 또는 활성 키가 없거나 키 길이가 잘못되면 애플리케이션
  시작을 실패시킨다.
- 저장 행의 키 버전이 키링에 없으면 해당 연결 접근만
  `BROKER_CREDENTIAL_UNAVAILABLE`로 실패한다.

### AES-GCM

- 알고리즘: `AES/GCM/NoPadding`
- nonce: 암호화마다 `SecureRandom`으로 생성한 12 bytes. 12 bytes는 이 시스템의
  고정 wire/storage 정책이며 JCA가 강제하는 길이라고 주장하지 않는다.
- authentication tag: 128 bits
- payload: format version + client ID UTF-8 길이/bytes + client secret UTF-8 길이/bytes
- AAD: `connectionId`, `userId`, `brokerType`, `credentialKeyVersion`,
  `credentialRevision`, payload format version
- 로컬 요청 제한: 각 값은 nonblank, UTF-8 기준 최대 4 KiB

client ID와 secret은 하나의 인증된 payload로 암호화해 교체의 원자성을 단순화한다.
복호화 시 알 수 없는 format, 길이 위반, tag 불일치, 잘못된 AAD는 모두 같은 일반
오류로 실패하며 원인을 API에 구분해 반환하지 않는다.

### 회전

1. 키링에 새 버전을 추가한다.
2. `active-key-version`을 새 버전으로 배포한다.
3. 새 생성·교체는 새 버전으로 암호화한다.
4. 기존 행은 저장된 이전 버전 키로 계속 읽는다.
5. 모든 이전 버전 행이 교체 또는 후속 재암호화 작업으로 사라진 것을 DB 집계로
   확인한 뒤 이전 키를 제거한다.

이번 범위는 5단계용 자동 batch/API를 만들지 않는다. 테스트는 이전 키 ciphertext
복호화와 새 활성 키 쓰기를 검증해 회전 가능한 형식만 보장한다.

## 8. TossCredentialProvider와 토큰 격리

기존 provider 계약을 metadata 조회와 실제 복호화로 분리한다.

```java
public interface TossCredentialProvider {
    TossCredentialMetadata current(UUID brokerConnectionId);
    TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision);
}
```

`DatabaseTossCredentialProvider` 흐름:

1. `current(id)`는 삭제되지 않은 `TOSS_INVEST` 연결의 revision projection만 읽고
   암호화 payload를 적재하거나 복호화하지 않는다.
2. `decrypt(id, expectedRevision)`는 동일 revision의 삭제되지 않은 토스 연결만
   조회해 복호화한다.
3. 행 없음, broker type 불일치, 삭제, revision 불일치, 키 없음, 복호화 실패는 모두
   자격증명을 반환하지 않는 fail-closed 예외다.

`TossTokenManager` 내부 token 계약은 revision을 보존한다.

```java
TossAccessToken getAccessToken(UUID brokerConnectionId);
void invalidateIfCurrent(
    UUID brokerConnectionId,
    long credentialRevision,
    String accessToken);
```

```text
broker:toss:oauth:v2:{brokerConnectionId}:{credentialRevision}
broker:toss:oauth:v2:{brokerConnectionId}:{credentialRevision}:lock
```

token 조회 순서:

```text
metadata = provider.current(connectionId)
expectedRevision = metadata.credentialRevision
tokenKey = connectionId + expectedRevision
cached token 조회
  └─ hit: 복호화 없이 TossAccessToken 반환
  └─ miss:
       revision 기반 분산 lock 획득
       cached token 재확인
       provider.decrypt(connectionId, expectedRevision)
         └─ revision 변경/삭제면 fail-closed
       OAuth token 발급
       provider.current(connectionId)로 expectedRevision 재확인
         └─ 변경/삭제면 발급 token 폐기, cache 저장 금지, fail-closed
       revision key에 token 저장
```

분산 lock 대기자는 같은 revision cache만 기다린다. lock 획득 후 cache 재확인에서
hit하면 복호화하지 않는다. OAuth 응답 뒤 revision 재확인은 경쟁 중 이전 자격증명으로
발급된 token을 반환하거나 cache에 저장하지 않기 위한 최종 방어다.

이 구조로:

- 교체 시 새 revision은 이전 토큰 key를 읽지 않는다.
- 삭제 시 `current()`가 먼저 실패하므로 남아 있는 Redis token도 사용하지 않는다.
- 캐시 hit 경로는 revision metadata만 읽고 자격증명을 복호화하지 않는다.
- cache miss 경로는 lock과 cache 재확인 뒤 exact revision이 일치할 때만 복호화한다.
- 교체·삭제가 복호화 전 또는 OAuth 응답 전 먼저 반영되면 token을 반환하지 않는다.
- 401 응답은 요청에 사용한 `TossAccessToken.credentialRevision`의 key만
  compare-and-delete하고, 다음 token 획득에서 provider의 최신 revision을 다시 읽는다.
- 별도 DB+Redis 분산 트랜잭션이나 best-effort cache 삭제가 필요 없다.

token acquisition은 최초 `current()`가 읽은 revision을 기준으로 선형화한다. 그
revision 확인 이후에 시작된 교체·삭제와 이미 진행 중인 외부 broker HTTP 요청의
취소는 보장하지 않는다. 다만 경쟁이 복호화 exact query 또는 OAuth 후 재확인에서
관찰되면 반드시 fail-closed한다.

cache hit마다 connection revision projection 조회 1회는 수행한다. 자격증명 복호화는
cache miss와 lock 재확인 miss에서만 수행한다. metadata cache는 추가하지 않는다.

## 9. REST API

모든 API는 인증과 CSRF를 요구한다. 요청의 사용자 ID는 body/header가 아니라
인증된 `Principal`에서만 얻는다.

최소 `SecurityFilterChain`은 다음을 강제한다.

- `/api/v1/broker-connections/**`는 `authenticated()`
- CSRF 활성화
- HTTP Basic과 form login 비활성화
- Spring Boot generated default user auto-configuration 제외
- 별도 인증 provider/session 구현이 없으면 모든 실제 요청 거부
- `Principal.name`이 UUID가 아니면 `403 AUTHENTICATED_USER_INVALID`

테스트의 `@WithMockUser`만 가짜 사용자를 공급한다. 운영 fallback
`UserDetailsService`, 고정 사용자, 기본 비밀번호는 만들지 않는다.

| Method | Path | 동작 |
|---|---|---|
| `POST` | `/api/v1/broker-connections/toss` | 자격증명 암호화 저장, `UNVERIFIED` 생성 |
| `PUT` | `/api/v1/broker-connections/{id}/credentials` | 소유권 확인 후 교체, revision 증가 |
| `POST` | `/api/v1/broker-connections/{id}/verify` | 토스 accounts read 호출로 연결 검증 |
| `DELETE` | `/api/v1/broker-connections/{id}` | 소유권 확인 후 암호문 제거·논리 삭제 |

생성/교체 요청:

```json
{
  "clientId": "write-only",
  "clientSecret": "write-only"
}
```

응답:

```json
{
  "id": "uuid",
  "brokerType": "TOSS_INVEST",
  "status": "UNVERIFIED",
  "credentialRevision": 1,
  "lastValidatedAt": null
}
```

응답에는 credential 존재 여부를 나타내는 별도 힌트도 넣지 않는다.

오류:

| 조건 | HTTP | 공개 코드 |
|---|---:|---|
| 미인증 | 401 | Spring Security 기본 경계 |
| CSRF 없음/권한 없음 | 403 | 공개 상세 없음 |
| 연결 없음 또는 다른 사용자 소유 | 404 | `BROKER_CONNECTION_NOT_FOUND` |
| 같은 사용자의 활성 토스 연결 중복 | 409 | `BROKER_CONNECTION_ALREADY_EXISTS` |
| 낙관적 락 또는 stale 검증 결과 | 409 | `BROKER_CONNECTION_CONFLICT` |
| 토스 인증·인가 검증 실패 | 422 | `BROKER_CONNECTION_VALIDATION_FAILED` |
| 키 없음·복호화 실패 | 503 | `BROKER_CREDENTIAL_UNAVAILABLE` |
| 토스 network/rate limit/5xx | 기존 normalized broker 오류 | 비밀값 없는 안정 코드 |

예외 메시지, validation message, DTO `toString()`은 필드명과 안정 코드만 포함하며
입력값을 포함하지 않는다.

## 10. 이벤트와 트랜잭션 흐름

### 생성

```text
authenticated user
  → local input validation
  → UUID/revision=1 결정
  → AES-GCM encrypt(AAD includes user/id/revision)
  → transaction: users anchor INSERT ON CONFLICT DO NOTHING
                 + BrokerConnection INSERT
  → secret 없는 response
```

`users` anchor는 인증된 Principal UUID에 대해서만 생성한다. 가입/로그인 정보는
저장하지 않으며 후속 identity migration이 같은 행을 확장한다. 중복 연결 경쟁은
partial unique index가 최종 차단한다.

### 교체

```text
transaction
  → findByIdAndUserId
  → next revision으로 새 payload 암호화
  → replaceCredentials()
  → status=UNVERIFIED, lastValidatedAt=null
  → optimistic version UPDATE
  → commit
```

암호화, flush, 낙관적 락 중 하나라도 실패하면 기존 ciphertext와 상태가 유지된다.

### 삭제

```text
transaction
  → findByIdAndUserId
  → credentialRevision 증가
  → ciphertext/nonce/keyVersion/lastValidatedAt null
  → status=DELETED, deletedAt 저장
  → optimistic version UPDATE
  → commit
```

PostgreSQL MVCC/WAL/backup의 과거 ciphertext 물리 제거는 이 작업의 보장이 아니다.

### 검증

외부 네트워크 호출 중 DB 트랜잭션을 유지하지 않는다.

```text
Tx 1: 소유권 확인 + connectionId/revision 읽기
  → BrokerAdapter.getAccounts(BrokerConnectionRef(id))
Tx 2: 같은 user/id/revision인지 재확인
  → 성공이면 ACTIVE + lastValidatedAt
  → 인증/인가 실패면 INVALID
  → revision이 바뀌었으면 409, 새 credential 상태는 변경하지 않음
```

이 패턴은 이전 credential의 늦은 성공 응답이 교체된 credential을 `ACTIVE`로 만드는
경쟁을 차단한다.

`BrokerConnectionRef` 계약은 UUID 하나만 가진다.

```java
public record BrokerConnectionRef(UUID brokerConnectionId) {}
```

broker type은 요청이나 reference에서 받지 않는다. 검증 service가 주입받은 Toss
adapter, 서비스 내부 `BrokerType.TOSS_INVEST` 상수, DB의
`broker_type = TOSS_INVEST` 조건으로만 결정한다.

## 11. 보안 경계

- `clientId`, `clientSecret`, decrypted payload, bearer token을 logger 인자로 넘기지 않는다.
- 평문 credential을 DB, API 응답, 로그, 예외, Redis 등 영속 캐시에 저장하지 않는다.
- request DTO와 `TossCredentials`의 `toString()`은 항상 마스킹한다.
- JPA entity는 secret getter와 전체 필드 `toString()`을 제공하지 않는다.
- API 응답/Problem Detail에는 ciphertext, nonce, key version, broker raw body를 넣지 않는다.
- `@ControllerAdvice`는 credential 관련 예외를 안정 코드로만 변환하며 exception
  message나 cause를 응답에 복사하지 않는다.
- 사용자 API repository 조회는 항상 `id + userId`로 수행하고 타 사용자와 미존재를
  동일한 `404`로 처리한다.
- 검증 API는 토스 read-only accounts 호출만 사용한다.
- 운영 provider bean이 없을 때 fallback/no-op credentials를 만들지 않는다.
- Vault가 비활성화되면 관리 API와 Toss adapter는 활성화되지 않는다.
- DB dump만으로는 복호화할 수 없도록 키는 DB 밖에서 주입한다.

JVM 메모리에서 평문이 즉시 제거된다고 보장하지 않는다. 현재 HTTP/Jackson/Spring
계약은 `String`을 사용하고, OAuth form 인코딩과 HTTP client 처리 과정에서 평문
credential의 추가 메모리 복사가 발생할 수 있다. `String`은 immutable이고 GC 시점과
메모리 덮어쓰기는 통제할 수 없으므로 다음은 비보장 사항이다.

- 요청 종료 즉시 heap에서 평문 제거
- 모든 중간 `String`, `byte[]`, encoder buffer의 zeroization
- heap dump, core dump, process memory inspection에서 평문 비노출

운영에서는 heap dump 접근권한과 생성·보관을 제한하고 crash artifact를 비밀
데이터로 취급한다. 이 운영 통제는 애플리케이션의 비노출 보장을 확장하지 않는다.

## 12. 테스트 설계

REST/권한 경계에 필요한 `spring-boot-starter-web`,
`spring-boot-starter-security`, test scope의 `spring-security-test`만 추가한다.
암호화 라이브러리는 추가하지 않는다.

### 암호화 단위 테스트

- round trip 후 client ID와 secret 일치
- 같은 평문을 두 번 암호화하면 nonce/ciphertext가 다름
- ciphertext, nonce, AAD를 각각 변조하면 복호화 실패
- 저장 key version 누락 시 fail-closed
- 이전 키 버전 ciphertext는 읽고 새 쓰기는 active version 사용
- payload 길이/format 위반 거부
- DTO/entity/exception 문자열에 알려진 canary secret이 없음

### PostgreSQL Testcontainers

- 빈 DB에 V1~V4 migration 및 incremental V3→V4 migration 성공
- secret shape check와 active `(user_id, broker_type)` unique 제약
- non-deleted 행의 `credential_key_version NULL` 거부
- JPA ciphertext round trip, 평문 미저장 확인
- `findByIdAndUserId` 타 사용자 조회 실패
- 삭제 시 암호화 컬럼 null, 상태/시각/revision 원자 저장
- 중복 생성 또는 flush 실패 시 부분 행 없음
- 동시 교체: 한 transaction만 성공, loser 전체 rollback
- 교체와 삭제 경쟁: 최종 행이 한 lifecycle 명령만 반영

### 서비스·권한·연동

- unauthenticated/CSRF 없는 mutation 차단
- 다른 사용자 create ID로 replace/verify/delete 모두 동일한 404
- 생성·교체·검증 응답과 오류 body에 canary secret 없음
- 복호화 실패와 누락 key에서 adapter HTTP 호출 0회
- Redis cache hit에서 `decrypt()`와 OAuth 호출 0회
- cache miss에서 lock 획득·cache 재확인 뒤에만 `decrypt()` 1회
- metadata 조회와 decrypt 사이 교체·삭제 시 token 발급 0회
- OAuth 응답 전 교체·삭제 시 token 반환/cache 저장 0회
- 토스 인증 실패는 revision이 같을 때만 `INVALID`
- network/rate limit/5xx는 상태를 바꾸지 않음
- verify 중 교체가 발생하면 늦은 verify 결과가 새 상태를 바꾸지 않음
- credential revision별 Redis cache/lock key 분리
- 삭제된 연결은 잔존 Redis token이 있어도 provider 단계에서 차단
- 이전 revision 요청의 늦은 401은 이전 token key만 제거하며 새 revision token을
  삭제하지 않음
- `BrokerConnectionRef`는 UUID 하나만 가지며 broker 문자열을 받지 않음

전체 완료 검증은 `./mvnw clean verify`로 수행한다.

## 13. 구현 순서

1. Flyway V4 제약 테스트와 migration
2. `BrokerConnection` 도메인/JPA/repository
3. `CredentialKeyring`과 `CredentialCipher`
4. metadata/decrypt 분리 provider, `TossAccessToken`, revision 기반 token key
5. 생성·교체·삭제 transaction service
6. 외부 호출과 DB transaction을 분리한 검증 service
7. Spring Security 경계와 REST controller
8. 권한·암호화·롤백·동시성·비노출 통합 테스트
9. 전체 회귀 검증

## 14. 명시적 비목표와 후속 조건

- 실주문 코드는 추가하지 않는다.
- 자동 sync worker를 추가하지 않는다.
- credential 재표시 API를 만들지 않는다.
- audit/outbox 원장을 이번 변경에 중복 구현하지 않는다. 공통 audit 모듈이 생길 때
  연결 생성·교체·검증·삭제의 secret 없는 메타데이터를 기록한다.
- KMS 도입 시 `CredentialCipher` 내부 키 획득만 교체하고 DB의 key version/AAD 계약을
  유지한다.
- 외부 공개 전 토스증권 약관상 다중 사용자 SaaS의 사용자별 credential 보관 허용
  여부를 운영 게이트로 다시 확인한다.

## 15. 보안 근거

- [Oracle Java 21 JCA Reference Guide](https://docs.oracle.com/en/java/javase/21/security/java-cryptography-architecture-jca-reference-guide.html):
  GCM은 AEAD이며 같은 key/IV 조합을 재사용하지 않고 AAD를 ciphertext 처리 전에
  제공해야 한다.
- [Java 21 `AEADBadTagException`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/AEADBadTagException.html):
  인증 태그 불일치는 복호화 실패로 처리된다.
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html):
  저장 데이터에는 AES와 GCM 같은 authenticated mode를 우선하고 키 회전 가능성을
  설계에 포함한다.
- [OWASP Key Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Key_Management_Cheat_Sheet.html):
  키의 생성, 배포, 회전, 폐기 생명주기와 접근 경계를 관리한다.
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html):
  credential과 같은 민감값을 로그에 기록하지 않는다.
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html):
  운영 secret은 환경 변수보다 config tree/mounted secret 사용을 우선한다.
- [Spring Boot Servlet Web Applications](https://docs.spring.io/spring-boot/reference/web/servlet.html):
  기본 오류 표현에 의존하지 않고 `@ControllerAdvice`로 공개 오류 필드를 제한한다.
