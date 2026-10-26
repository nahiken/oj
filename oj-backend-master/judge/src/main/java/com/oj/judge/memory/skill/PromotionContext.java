package com.oj.judge.memory.skill;

/**
 * 一次晋升的「谁审的、从哪条来」。
 *
 * <p>为什么要这个小类型：`actorUid` 与 `originId` 都是 {@code Long}，挨着传参时换位不会报错，
 * 只会让审计行静默记错人（例如把系统扫描写成某个用户）。包成一个类型后，调用点写的是
 * `new PromotionContext(actorUid, skillId)`，参数顺序由 record 定义固定住。
 */
public record PromotionContext(long actorUid, long originId) {
}
