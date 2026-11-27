-- Duas campanhas publicadas de mesma prioridade não podem ter períodos sobrepostos (início inclusivo, fim exclusivo:
-- uma pode começar no instante em que a outra termina). A API confere antes e responde qual é o conflito; esta
-- constraint é a garantia final.
--
-- int4range(priority, priority, '[]') && ... significa "mesma prioridade" sem depender da extensão btree_gist.
-- Datas nulas não chegam aqui: chk_campaigns_published as exige em campanha publicada.
ALTER TABLE campaigns
  ADD CONSTRAINT excl_campaigns_overlap
  EXCLUDE USING gist (
    int4range(priority, priority, '[]') WITH &&,
    tstzrange(starts_at, ends_at)       WITH &&
  ) WHERE (status = 'PUBLISHED');
