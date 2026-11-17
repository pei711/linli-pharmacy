ALTER TABLE seckill_request
    DROP CHECK ck_request_status,
    ADD CONSTRAINT ck_request_status CHECK (status IN ('INITIALIZING','PROCESSING','SUCCESS','FAILED','EXPIRED','CANCELLED'));
