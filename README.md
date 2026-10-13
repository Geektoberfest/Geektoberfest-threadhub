# ThreadHub — Community Discussion Platform

ThreadHub is a Reddit-inspired community discussion platform built with Spring Boot, PostgreSQL, React, Vite, and Tailwind CSS. It provides the foundation for users to create accounts, authenticate securely, create and discover communities, join or leave communities, and manage community membership.

---

## Live Deployment

- **Frontend:** https://threadhub-silk.vercel.app
- **Backend API:** https://threadhub-production.up.railway.app
- **Repository:** https://github.com/tanishqkale91-cmd/threadhub

---

## 1. Application Architecture

```
threadhub/
├── .github/workflows/ci.yml       # GitHub Actions CI pipeline
├── frontend/                       # React 19 + Vite 8 + Tailwind CSS v4 frontend
│   ├── src/
│   │   ├── api/                   # API client layer (authApi, communityApi, userApi)
│   │   ├── components/            # Reusable UI components & layouts
│   │   ├── context/               # AuthContext for session management
│   │   └── pages/                 # Route components (Home, Communities, Auth)
│   ├── package.json
│   └── vite.config.js
└── threadhub/                      # Java 25 + Spring Boot 4.1.1 backend
    ├── src/main/java/com/threadhub/
    │   ├── config/                # SecurityConfig & CORS configuration
    │   ├── controller/            # Auth, User, and Community REST controllers
    │   ├── dto/                   # Request/Response contracts & Bean Validation
    │   ├── exception/             # GlobalExceptionHandler & custom domain exceptions
    │   ├── model/                 # JPA Entities (User, Community, CommunityMember)
    │   ├── repository/            # Spring Data JPA repositories
    │   ├── security/              # JwtService & SecurityUtils helpers
    │   └── service/               # Core business logic services
    ├── pom.xml
    └── application.properties
```

---

## 2. Authentication & Authorization Model

ThreadHub uses **Spring Security** with **OAuth2 Resource Server** and **JWT (JSON Web Tokens)** signed using HMAC-SHA256 (`HS256`).

- **Authentication Flow**:
  1. Clients authenticate via `POST /api/auth/login` with email and password.
  2. The server returns a short-lived JWT **access token** (15 minutes) and an opaque, single-use **refresh token** (7 days).
  3. Clients attach the access token to subsequent requests using the standard header: `Authorization: Bearer <token>`.
  4. Before the access token expires, clients exchange the refresh token at `POST /api/auth/refresh` for a new pair.

- **Token Rotation & Revocation**:
  - Refresh tokens are random 256-bit values; only their SHA-256 digest is stored, never the token itself.
  - Every refresh **rotates** the token: the presented one is consumed and a replacement is issued in the same *family*.
  - Presenting an already-used token is treated as replay and revokes the whole family, so a stolen copy cannot outlive its detection. Concurrent duplicate refreshes therefore cannot both succeed.
  - `POST /api/auth/logout` revokes the caller's refresh-token family **and** blacklists the access token by its `jti`, so a logged-out token stops working immediately. Revocations are persisted, so they apply across every application instance and are purged once the token can no longer be valid.

- **Access Policy**:
  - **Public Endpoints**:
    - `POST /api/users` (User registration)
    - `POST /api/auth/login` (User login)
    - `POST /api/auth/refresh` (Exchange a refresh token for new credentials)
    - `GET /api/communities` (List all communities)
    - `GET /api/communities/{id}` (Get community details)
    - `GET /api/communities/name/{name}` (Get community by name)
    - `GET /api/communities/{id}/members` (List community members)
  - **Protected Endpoints** (Requires valid Bearer token):
    - `GET /api/users/{id}` (User profile lookup)
    - `POST /api/communities` (Create new community)
    - `POST /api/communities/{id}/join` (Join community)
    - `DELETE /api/communities/{id}/leave` (Leave community)
    - `GET /api/communities/{id}/membership` (Get current user membership status)
    - `POST /api/auth/logout` (Revoke the current session)

---

## 3. Environment Variables & Setup

### Backend Environment Variables (`./threadhub/.env`)

| Variable Name | Description | Example / Default |
|---------------|-------------|-------------------|
| `DB_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://localhost:5432/threadhub` |
| `DB_USERNAME` | PostgreSQL database user | `threadhub_user` |
| `DB_PASSWORD` | PostgreSQL database password | `threadhub_dev` |
| `JWT_SECRET` | 256-bit (32+ byte) secret key | `404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970` |
| `JWT_ACCESS_TOKEN_EXPIRATION` | Access-token lifetime in **milliseconds** | `900000` (15 minutes) |
| `JWT_REFRESH_TOKEN_EXPIRATION` | Refresh-token lifetime in **milliseconds** | `604800000` (7 days) |
| `JWT_TOKEN_CLEANUP_INTERVAL` | ISO-8601 interval for purging expired token bookkeeping | `PT1H` |

> **Upgrading an existing deployment:** the access-token property was renamed from `jwt.expiration`, and its default
> dropped from 24 hours to 15 minutes. Set `JWT_ACCESS_TOKEN_EXPIRATION` explicitly if you need a value other than the
> default, and note that clients must now refresh credentials instead of holding a single long-lived token.

### Token storage schema

Two tables back the token lifecycle: `refresh_tokens` (hashed refresh tokens with their family, expiry and consumed/revoked
state) and `revoked_access_tokens` (blacklisted `jti` values with the moment they would have expired).

On this branch Hibernate owns the schema (`spring.jpa.hibernate.ddl-auto=update`), so both tables are created on startup.
If/when the project switches to Flyway with `ddl-auto=validate`, the equivalent migration must create them:

```sql
create table refresh_tokens (
    id bigint generated by default as identity primary key,
    user_id bigint not null references users (id),
    token_hash varchar(64) not null unique,
    family_id varchar(36) not null,
    issued_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone not null,
    consumed_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    replaced_by_hash varchar(64)
);
create index idx_refresh_tokens_family_id on refresh_tokens (family_id);
create index idx_refresh_tokens_user_id on refresh_tokens (user_id);

create table revoked_access_tokens (
    id bigint generated by default as identity primary key,
    jti varchar(64) not null unique,
    user_id bigint,
    expires_at timestamp(6) with time zone not null,
    revoked_at timestamp(6) with time zone not null
);
create index idx_revoked_access_tokens_expires_at on revoked_access_tokens (expires_at);
```

### Frontend Environment Variables (`./frontend/.env`)

```env
VITE_API_BASE_URL=http://localhost:8080
```

---

## 4. How to Run & Test

### Backend (Spring Boot)
```bash
cd threadhub

# Run test suite
DB_URL=jdbc:postgresql://localhost:5432/threadhub \
DB_USERNAME=threadhub_user \
DB_PASSWORD=threadhub_dev \
JWT_SECRET=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970 \
./mvnw test

# Start backend server
DB_URL=jdbc:postgresql://localhost:5432/threadhub \
DB_USERNAME=threadhub_user \
DB_PASSWORD=threadhub_dev \
JWT_SECRET=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970 \
./mvnw spring-boot:run
```

### Frontend (React / Vite)
```bash
cd frontend

# Install dependencies
npm install

# Run Oxlint linter
npm run lint

# Build production bundle
npm run build

# Start dev server
npm run dev
```

---

## 5. Standard Error Response Convention

All application-level API errors return a standardized `ErrorResponse` payload:

```json
{
  "timestamp": "2026-10-10T12:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "details": [
    "Username must be between 3 and 50 characters",
    "Invalid email format"
  ]
}
```

### Standard Status Codes
- `400 Bad Request`: Validation failure, malformed JSON body, invalid path parameter type, business rule violation.
- `401 Unauthorized`: Missing authentication, expired token, invalid token signature, invalid login credentials.
- `403 Forbidden`: Access denied.
- `404 Not Found`: User or Community resource not found.
- `409 Conflict`: Duplicate username, duplicate email, duplicate community name, or already a member.
- `500 Internal Server Error`: Generic unhandled server-side failure (log trace suppressed from client output).

---

## 6. Guidelines for External Contributors

When implementing reserved issues:
- **Issue #1**: Post CRUD APIs
- **Issue #2**: Home and Community Feeds
- **Issue #3**: Comments & Replies
- **Issue #4**: Post Voting
- **Issue #5**: User Profiles

**Rules**:
1. Keep controllers thin; execute business logic in transaction-scoped `@Service` classes.
2. Define request validation rules on DTO classes (`@Valid @RequestBody`), never directly on entity objects.
3. Derive identity from `SecurityUtils.getCurrentUserId()` rather than accepting trusted client user IDs.
4. Ensure all new endpoints register appropriate exception handling in `GlobalExceptionHandler`.
5. Maintain unit and integration test coverage for both success and error paths.

---

## Technology Stack

| Layer | Technology |
|---|---|
| Frontend | React 19 |
| Build Tool | Vite 8 |
| Styling | Tailwind CSS v4 |
| Routing | React Router v7 |
| Backend | Spring Boot 4.1.1 |
| Language | Java 25 |
| Security | Spring Security + JWT |
| ORM | Spring Data JPA + Hibernate |
| Database | PostgreSQL |
| Production DB | Neon PostgreSQL |
| Backend Hosting | Railway |
| Frontend Hosting | Vercel |
| Build System | Maven |
| CI Pipeline | GitHub Actions |
