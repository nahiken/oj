-- 推进 fencing token：原子 INCR，且结果必须大于状态哈希里的 epoch（契约 0003 §2.3）
-- KEYS[1] = judge:epoch:{submitId}
-- KEYS[2] = judge:state:{submitId}（可能不存在）
-- ARGV[1] = ttlSeconds
-- 返回新的 epoch
--
-- 为什么需要状态哈希兜底：judge:epoch:* 是单独的 String key，可能被 evict/手工清理，
-- 而状态哈希还活着（24h）。只做 INCR 会从 1 重新开始，旧 worker 更高的 epoch 反而重新有效。
-- INCR 后再与状态值比较并补到 stateEpoch+1，整个过程在一个脚本里完成，不会留并发窗口。
local next = redis.call('INCR', KEYS[1])
local raw = redis.call('HGET', KEYS[2], 'epoch')
local stateEpoch = tonumber(raw or '0') or 0
if next <= stateEpoch then
  next = stateEpoch + 1
  redis.call('SET', KEYS[1], next)
end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
return next
