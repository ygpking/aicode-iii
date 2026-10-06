-- 上下文压缩块 id：同一次压缩写入的 head 消息、marker 与摘要共持同一 id，
-- 供「恢复已压缩区间」时作为整体翻转（head 的 isCompacted 置 0、marker/summary 的置 1）。
-- 存量行为 null：老压缩没有块边界记录，其区间不可恢复（有意接受的降级）。
ALTER TABLE agent_messages ADD COLUMN compactionBlockId TEXT DEFAULT NULL;
