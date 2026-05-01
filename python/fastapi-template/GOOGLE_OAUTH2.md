# Google OAuth2 Login

This project supports two authentication methods:
- **JWT login** — email/password, issues its own tokens
- **Google OAuth2** — login with Gmail, issues the same JWT tokens

---

## How the Flow Works

```
1. User calls GET /api/v1/auth/google
        │
        ▼
2. FastAPI returns JSON: { "url": "https://accounts.google.com/o/oauth2/v2/auth?..." }
        │
        ▼
3. User opens that URL in browser → Google consent page
   "Allow fastapi-template to access your Google account?"
        │
        ▼  (user clicks Allow)
4. Google redirects browser to:
   http://localhost:8000/api/v1/auth/google/callback?code=XXXX
        │
        ▼
5. FastAPI callback handler:
   a. Exchanges the code for a Google access token
   b. Calls Google UserInfo API → gets { id, email, name }
   c. Looks up user in DB by google_id
      - Found    → use existing user
      - Not found, email exists → link google_id to existing account
      - Not found, new email   → create new account (role=user)
   d. Issues our own JWT access_token + refresh_token
        │
        ▼
6. Browser receives JSON response:
   {
     "access_token": "eyJ...",
     "refresh_token": "eyJ...",
     "token_type": "bearer"
   }
```

---

## Setup — Google Cloud Console

You only need to do this once.

1. Go to https://console.cloud.google.com
2. Create a project (or use an existing one)
3. **APIs & Services → OAuth consent screen**
   - Choose **External**
   - Fill in app name and your email
   - Save
4. **APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID**
   - Application type: **Web application**
   - Authorized redirect URIs: `http://localhost:8000/api/v1/auth/google/callback`
   - Click Create → copy Client ID and Client Secret
5. Add to your `.env` file (never commit this file):

```env
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret
GOOGLE_REDIRECT_URI=http://localhost:8000/api/v1/auth/google/callback
```

---

## How to Test Locally

### Step 1 — Start the stack

```bash
docker compose up --build -d
```

### Step 2 — Get the Google login URL

**Via Swagger UI:**
- Open http://localhost:8000/docs
- Find `GET /api/v1/auth/google`
- Click **Try it out → Execute**
- Copy the `url` value from the response

**Via curl:**
```bash
curl http://localhost:8000/api/v1/auth/google
# returns: {"url": "https://accounts.google.com/o/oauth2/v2/auth?..."}
```

> **Why not redirect directly?**
> Swagger UI uses `fetch()` internally which cannot follow cross-origin redirects to Google (CORS block). Returning the URL as JSON lets you copy and open it manually — and is also the correct design for a REST API consumed by any frontend.

### Step 3 — Open the URL in your browser

Copy the `url` value from the response and paste it into your browser address bar.

### Step 3 — Google consent page

- Select your Gmail account
- Click **Allow**

### Step 4 — Get your tokens

After clicking Allow, Google redirects to the callback URL. FastAPI processes it and returns:

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "bearer"
}
```

### Step 5 — Use the token

**In Swagger UI:**
- Click the **Authorize** button (top right of Swagger)
- Paste the `access_token` value
- Click Authorize
- All protected endpoints now send `Authorization: Bearer <token>` automatically

**With curl:**
```bash
curl http://localhost:8000/api/v1/users/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

## Account Linking Rules

| Scenario | Result |
|---|---|
| First login with Google (new email) | New account created with `role=user` |
| Google email matches existing account | `google_id` linked to existing account |
| Login again with same Google account | Existing user returned, no duplicate created |
| Google-only account tries password login | Will fail — `hashed_password` is `null` |

---

## Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/auth/google` | Redirects browser to Google consent page |
| `GET` | `/api/v1/auth/google/callback` | Handles Google callback, returns JWT tokens |

---

## Files Changed

| File | Change |
|---|---|
| `app/core/config.py` | Added `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` settings |
| `app/models/user.py` | Added `google_id` column (nullable), made `hashed_password` nullable |
| `app/repositories/user_repository.py` | Added `find_by_google_id()` |
| `app/api/v1/routes/google_auth.py` | New file — the two OAuth2 endpoints |
| `app/api/v1/router.py` | Registered `google_auth` router |
| `.env` | Added real credentials (git-ignored) |
| `.env.example` | Added placeholder keys for documentation |
