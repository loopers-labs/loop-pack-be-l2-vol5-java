-- 작업 큐를 비우고 소비자 그룹을 새로 만든다. 발급 요청과 처리 중인 작업이 없을 때(준비 시점)만 부른다.
-- 큐에는 만료를 걸지 않는다. 처리 전 작업이 시간만으로 사라지지 않게, 다 처리된 뒤 cleanup이 지운다.
-- KEYS[1] = jobs(stream). ARGV[1] = 소비자 그룹 이름
redis.call('DEL', KEYS[1])
redis.call('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
return 1
