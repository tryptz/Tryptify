-- play_history: keep each user's newest 2,000 rows
-- ================================================
--
-- Applied to production as migration play_history_prune_uses_index.
-- Idempotent, so it is safe to re-run.
--
-- The trigger prune_play_history_after_insert (AFTER INSERT, FOR EACH ROW)
-- runs this on every row the app inserts. It used to delete with
--
--   id not in (select id ... order by played_at desc limit 2000)
--
-- which hashes the newest 2,000 ids and then checks every one of the user's
-- rows against them: 62-87 ms per insert, and a third of all the database
-- time the app used. This form walks the (user_id, played_at desc) index to
-- the 2,000th-newest timestamp once and deletes everything older through the
-- same index: 1.4 ms for a user at the cap, and next to nothing for a user
-- under it, where the subquery finds no row and the comparison with null
-- matches nothing.
--
-- Rows tied with the 2,000th timestamp are kept, so a user can briefly hold a
-- row or two over 2,000. played_at defaults to now(), the transaction's start
-- time, so a tie needs two inserts in one transaction.

create or replace function public.prune_play_history()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from public.play_history
    where user_id = new.user_id
      and played_at < (
          select h.played_at
          from public.play_history h
          where h.user_id = new.user_id
          order by h.played_at desc
          offset 1999
          limit 1
      );
    return null;
end;
$$;
