ALTER TABLE inbound_document_review
    DROP INDEX uk_inbound_review_document,
    ADD INDEX idx_inbound_review_document (document_id, id);
