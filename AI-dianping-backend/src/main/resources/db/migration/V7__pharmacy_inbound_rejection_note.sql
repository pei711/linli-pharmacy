ALTER TABLE inbound_document
    ADD COLUMN review_note VARCHAR(1000) NOT NULL DEFAULT '' AFTER status;
