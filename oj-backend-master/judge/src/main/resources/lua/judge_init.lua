-- 初始化判题状态键：已存在则什么都不做（幂等）
-- KEYS[1] = judge:state:{submitId}
-- ARGV[1] = uid, ARGV[2] = problemId, ARGV[3] = nowMillis
-- ARGV[4] = ttlSeconds, ARGV[5] = judgeInfoJson
-- 返回 0 = 已创建, 1 = 已存在
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 1
end
redis.call('HSET', KEYS[1],
  'status', '0',
  'version', '0',
  'attempt', '0',
  'epoch', '0',
  'uid', ARGV[1],
  'problemId', ARGV[2],
  'ji', ARGV[5],
  'createdAt', ARGV[3],
  'updatedAt', ARGV[3],
  'dbSynced', '1')
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
return 0
