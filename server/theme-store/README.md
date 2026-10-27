# Theme Store server

The API behind the app's Theme Store. `index.ts` is the whole service: one
file, run by Bun as a Railway Function, with its own Railway Postgres. The app
calls it from `ThemeStoreRepository`, at the address in the `THEME_STORE_URL`
build setting (`themeStore.url` in `local.properties` overrides it).

## Where it runs

Railway project **humble-charm**, environment **production**:

| Service       | What it is                                                  |
| ------------- | ----------------------------------------------------------- |
| `theme-store` | `index.ts`, at the address the app's `THEME_STORE_URL` names |
| `Postgres`    | The packs, their pictures, likes, downloads and reports     |

`theme-store` has one variable set, `DATABASE_URL = ${{Postgres.DATABASE_URL}}`
(a reference, so the password never leaves Railway). `SUPABASE_URL` and
`SUPABASE_ANON_KEY` can also be set; left out, they are the app's own project
and its public key. It creates its tables on start, and waits up to a minute
for the database if it is still starting.

## Sign-in

There is no account system here. The app is signed in with Supabase, and every
request carries that session's access token. How the server checks it depends
on what signed it:

- **An asymmetric key (ES256, RS256).** Checked here, against the project's
  **public** keys (`/auth/v1/.well-known/jwks.json`), its issuer and the
  `authenticated` audience; the person is the token's `sub`.
- **The project's shared secret (HS256).** This service does not have that
  secret, so it asks Supabase Auth (`GET /auth/v1/user`, with the same public
  key the app ships) who the token belongs to, and remembers the answer until
  the token expires, five minutes at most.

A project can publish an ES256 key while still signing with its shared secret
(the new key waiting as a standby), so both have to work. Either way the service
holds no Supabase secret, and nothing in it can sign in as anyone. If Supabase
Auth does not answer, the store says `auth_unreachable` (502) rather than
`signed_out`, so the app does not throw away a session that may be fine.

## Routes

`GET /health` (and `GET /`) answers without an account, with whether it has a
database. Everything under `/v1` needs one. A pack's `user_id` is its author's
account id only when the author asks; to anyone else it is `""`, so the store
hands out nobody's account id (the app needs it only to say "yours"):

| Route                          | Does                                                    |
| ------------------------------ | ------------------------------------------------------- |
| `GET /v1/packs?sort=&q=&page=` | The store, 20 a page. `sort` is `popular`, `new` or `mine`; `q` searches names |
| `GET /v1/packs/:id`            | One pack, the pack itself included                      |
| `GET /v1/codes/:code`          | A pack by its share code, listed or not                 |
| `POST /v1/packs`               | Publish. At most 50 per person; the pack at most 64 KB  |
| `PATCH /v1/packs/:id`          | `{"listed": bool}`, author only                         |
| `DELETE /v1/packs/:id`         | Author only                                             |
| `GET /v1/likes?ids=`           | Which of those ids (up to 100) this person likes        |
| `PUT` / `DELETE /v1/packs/:id/like` | Like, unlike                                       |
| `POST /v1/packs/:id/download`  | Counted once per person and pack                        |
| `POST /v1/packs/:id/report`    | One report per person and pack                          |
| `GET /v1/packs/:id/snapshot`   | The pack's store picture                                |
| `PUT /v1/packs/:id/snapshot`   | Set or replace it, author only: the image as the body   |

A published pack has a name of 1 to 40 characters, a description of up to 280,
1 to 16 of the theme parts (nine exist: the CRT effect is the ninth), and
the pack itself as a JSON string. Its share code
is 8 characters, with no 0, O, 1 or I. No request body may be over 192 KB.

A store picture is the phone-shaped poster the app draws of a pack with its own
renderers when it is shared (lyrics, player, and the mini player and tab bar).
The app sends it as WebP, 540 pixels across and at most 150 KB; the server
takes up to 160 KB. It takes WebP, PNG or JPEG by their first bytes, whatever
the upload says it is, and answers with the picture's new hash. Every listing
row carries `snapshot`, a short hash of the picture or null, and the app keeps
pictures by that hash, so a replaced picture is fetched once and an unchanged
one never again.

Errors are `{"error": code}`: `signed_out` (401), `bad_pack`, `bad_image` and
`bad_request` (400, a `PATCH` without a true or false `listed`), `not_found`
(404), `limit_reached` (409), `too_big` (413), `server_error` (500),
`auth_unreachable` (502), `not_set_up` (503, no database) and `busy` (503, no
free share code in five tries). `StoreWire.errorOf` in the app reads
`signed_out`, `limit_reached`, `bad_pack` and `too_big` (both as a bad pack),
`not_found` and `not_set_up`. Anything else, `auth_unreachable` included, is
treated as the network failing, so the app keeps its session; a 401 with no
code is still signed out.

The list of theme parts the server accepts and the 64 KB pack cap have to match
the app's `ThemePart` and `ThemePackCodec.MAX_BYTES`, the app's picture cap
(`SnapshotCodec.MAX_BYTES`) has to fit under the server's, and the body limit
has to hold a picture and twice a pack. `ThemeStoreWireTest` reads `index.ts`
and fails if they drift apart.

A table made before the CRT part held `parts` to eight. Every start drops that
check and adds it again at `MAX_PARTS` (16), so an old database takes a
nine-part pack after one deploy, and the pair of statements is safe to run on
every start.

## Testing

Against a throwaway local Postgres (it drops the store's tables), with a
stand-in for Supabase Auth — public keys for a signing key made in the test,
and its "who is this" endpoint:

```
TEST_DATABASE_URL=postgres://postgres@127.0.0.1:55432/store bun run test.ts
```

It is a plain script rather than `bun test`, because the test runner does not
fetch versioned imports like `hono@4` and a plain run does — the same way
Railway runs `index.ts`.

## Deploying a change

Railway Functions hold their code themselves; nothing deploys from this
repository on push. After changing `index.ts`, run the test, then replace the
function's code with the whole file (the Railway dashboard's editor, or the
Railway MCP's `update-function-source-code`), and check `/health` afterwards.
This file is the source of truth: never edit the deployed code only.
