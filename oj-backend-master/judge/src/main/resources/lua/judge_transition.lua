-- 状态迁移 CAS：校验 status/version/epoch 后原子迁移，版本号自增
-- KEYS[1] = judge:state:{submitId}
-- ARGV[1] = expectedStatus (-1 = 不限)
-- ARGV[2] = expectedVersion (-1 = 不限)
-- ARGV[3] = targetStatus
-- ARGV[4] = workerId ('' = 不写)
-- ARGV[5] = attempt  ('' = 不写)
-- ARGV[6] = nowMillis
-- ARGV[7] = ttlSeconds
-- ARGV[8] = judgeInfoJson ('' = 不写)
-- ARGV[9] = leaseUntilMillis ('' = 不写)
-- ARGV[10] = expectedEpoch ('' = 不校验；fencing token，见 0003)
-- ARGV[11] = newEpoch ('' = 不写；必须严格大于当前 epoch，否则返回 3)
-- 返回 -1 = Key 不存在, 0 = 成功, 1 = status 不匹配, 2 = version 不匹配, 3 = epoch 不匹配（被 fence）
if redis.call('EXISTS', KEYS[1]) == 0 then
  return -1
end
-- 缺省参数按 '' 处理：Java 端总是传满 11 个，但脚本要能被「省略尾部参数」的客户端安全调用。
-- 不能删这个 helper：Windows 的 redis-cli --eval 会丢掉空参数，fence 场景与手工排查都依赖它。
local function s(i) return ARGV[i] or '' end
local curStatus = tonumber(redis.call('HGET', KEYS[1], 'status'))
local curVersion = tonumber(redis.call('HGET', KEYS[1], 'version')) or 0
local curEpoch = tonumber(redis.call('HGET', KEYS[1], 'epoch')) or 0
local expStatus = tonumber(ARGV[1])
local expVersion = tonumber(ARGV[2])
if expStatus >= 0 and curStatus ~= expStatus then
  return 1
end
if expVersion >= 0 and curVersion ~= expVersion then
  return 2
end
if s(10) ~= '' and curEpoch ~= tonumber(s(10)) then
  return 3
end
-- newEpoch 必须严格递增（契约 0003 §2.3）：过期的 nextEpoch 值或 Redis 丢 key 后的回退值
-- 都不能把 epoch 写小，否则旧 worker 的 epoch 会重新有效。校验放在任何写入之前。
if s(11) ~= '' then
  local newEpoch = tonumber(s(11))
  if newEpoch <= curEpoch then
    return 3
  end
end
local newVersion = curVersion + 1
redis.call('HSET', KEYS[1], 'status', ARGV[3], 'version', newVersion, 'updatedAt', ARGV[6])
if s(4) ~= '' then redis.call('HSET', KEYS[1], 'workerId', s(4)) end
if s(5) ~= '' then redis.call('HSET', KEYS[1], 'attempt', s(5)) end
if s(8) ~= '' then redis.call('HSET', KEYS[1], 'ji', s(8)) end
if s(9) ~= '' then redis.call('HSET', KEYS[1], 'leaseUntil', s(9)) end
if s(11) ~= '' then redis.call('HSET', KEYS[1], 'epoch', s(11)) end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[7]))
return 0
