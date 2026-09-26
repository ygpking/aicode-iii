-- 新增 durable_tasks 表：长任务生命周期状态，供崩溃恢复判定（不自动重跑，仅标记可恢复）。
CREATE TABLE IF NOT EXISTS `durable_tasks` (
  `id` TEXT NOT NULL,
  `sessionId` TEXT NOT NULL,
  `state` TEXT NOT NULL,
  `promptSnippet` TEXT NOT NULL,
  `round` INTEGER NOT NULL,
  `createdAt` INTEGER NOT NULL,
  `updatedAt` INTEGER NOT NULL,
  PRIMARY KEY(`id`)
);
CREATE INDEX IF NOT EXISTS `index_durable_tasks_sessionId` ON `durable_tasks` (`sessionId`);
CREATE INDEX IF NOT EXISTS `index_durable_tasks_state` ON `durable_tasks` (`state`);
