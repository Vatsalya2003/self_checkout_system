-- checkout-service: writes sales. UPDATE is limited to the two columns the
-- saga's tx B changes; DELETE is needed for the one rollback case (inventory
-- definitely never received the commit, so the PENDING rows are removed and
-- the basket reopens).
GRANT USAGE ON SCHEMA public TO checkout_svc;
GRANT SELECT, INSERT, DELETE, UPDATE (status, completed_at) ON transactions      TO checkout_svc;
GRANT SELECT, INSERT, DELETE, UPDATE (applied_quantity)     ON transaction_items TO checkout_svc;
