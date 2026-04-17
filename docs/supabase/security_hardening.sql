-- Supabase Security Advisor fixes.
-- Apply in the SQL editor on the production project.
--
-- 1) Function Search Path Mutable (3 warnings)
--
-- Functions that don't pin search_path are vulnerable to search-path
-- attacks: a role that can create objects in a schema earlier in the
-- search path could shadow built-ins used inside the function. Lock
-- the path to an empty search path so every reference in the body
-- must be schema-qualified (safest).
--
-- If either function references `public.*` tables by unqualified name
-- they'll need to be updated to qualify those references after this
-- runs. Run `\sf public.<name>` in psql (or read the body in the
-- dashboard) to check before applying if you're unsure.

alter function public.set_updated_at()       set search_path = '';
alter function public.handle_updated_at()    set search_path = '';
alter function public.prune_play_history()   set search_path = '';

-- If any of the above errors with "has unqualified reference to ...",
-- relax to a minimal explicit path instead:
--   alter function public.<name>() set search_path = public, pg_catalog;

-- 2) Leaked Password Protection
--
-- Not a SQL change — toggle in the dashboard:
--   Auth → Policies → "Enable leaked password protection" (HaveIBeenPwned)
-- Once on, Supabase rejects new sign-ups / password changes that match
-- known breached passwords. No code change needed on the Android side;
-- the existing Appwrite/Supabase OAuth path is unaffected because OAuth
-- sign-ins don't go through this check.
