-- 초안의 수정 번호. 화면이 읽은 번호와 지금 번호가 다르면 그사이 다른 곳에서 고친 것이라 저장·판정을 받지 않는다.
-- - 판 번호(scenario_version)와 따로 센다. 판 번호는 [수정하기]로 새 판을 만들 때만 오르고, 이 번호는 같은 초안을 저장할 때마다 오른다.
-- - 작은 목표만 고쳐도 서버가 이 번호를 올린다.
ALTER TABLE scenario ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
