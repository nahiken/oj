-- 续租：只有持有者能延长自己的锁与租约（token 不匹配即丢失）
-- KEYS[1] = judge:lock:{submitId}
-- KEYS[2] = judge:state:{submitId}
-- ARGV[1] = token, ARGV[2] = ttlMillis, ARGV[3] = nowMillis, ARGV[4] = leaseUntilMillis
-- 返回 1 = 续租成功, 0 = 不是持有者（锁已过期或被接管）
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
if redis.call('EXISTS', KEYS[2]) == 1 then
  redis.call('HSET', KEYS[2], 'leaseUntil', ARGV[4])
end
return 1
