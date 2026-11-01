CREATE TABLE staff_identity_lock (
    lock_key INTEGER PRIMARY KEY,
    CONSTRAINT ck_staff_identity_lock_singleton CHECK (lock_key = 1)
);

INSERT INTO staff_identity_lock(lock_key) VALUES (1);
