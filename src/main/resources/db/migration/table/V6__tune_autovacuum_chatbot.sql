-- 1. Bảng chat_messages (INSERT-heavy, Phình to rất nhanh)
ALTER TABLE chat_messages SET (
  autovacuum_vacuum_scale_factor   = 0.05,
  autovacuum_vacuum_threshold      = 50,
  autovacuum_analyze_scale_factor  = 0.02,
  autovacuum_analyze_threshold     = 50,
  
  -- Kích hoạt Vacuum định kỳ cả khi CHỈ CÓ INSERT để tránh Wraparound
  autovacuum_vacuum_insert_scale_factor = 0.05, 
  autovacuum_vacuum_insert_threshold    = 50,
  
  autovacuum_vacuum_cost_delay     = 2
);

-- 2. Bảng chat_sessions (Tốc độ tăng chậm hơn nhưng nên đồng bộ)
ALTER TABLE chat_sessions SET (
  autovacuum_vacuum_scale_factor   = 0.05,
  autovacuum_vacuum_threshold      = 50,
  autovacuum_analyze_scale_factor  = 0.02,
  autovacuum_analyze_threshold     = 50,
  
  autovacuum_vacuum_insert_scale_factor = 0.05, 
  autovacuum_vacuum_insert_threshold    = 50,
  
  autovacuum_vacuum_cost_delay     = 2
);
