-- 释放认领锁：只有持有者（token 匹配）才能删，避免误删别人的锁
-- KEYS[1] = judge:lock:{submitId}
-- ARGV[1] = token = workerId#epoch
-- 返回 1 = 已删除, 0 = 不是自己的锁
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
