ALTER TABLE inbound_document_review
    ADD COLUMN purchase_unit_price DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER quantity,
    ADD CONSTRAINT ck_inbound_review_purchase_price CHECK (purchase_unit_price >= 0);
