-- Apply after 001_operations.sql to support all five journal command types.
BEGIN;
ALTER TABLE operations DROP CONSTRAINT operations_operation_type_check;
ALTER TABLE operations ADD CONSTRAINT operations_operation_type_check
    CHECK (operation_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'CHARGE_FEE', 'REVERSAL'));
COMMIT;
