-- V2 — Seed reference data for dev/staging environments
-- Production data seeding is handled via the onboarding runbook, not migrations

-- Insert sample cases for smoke testing
INSERT INTO flowops.customer_cases
    (id, case_number, customer_id, customer_name, status, priority, case_type,
     subject, description, sla_due_at)
VALUES
    (gen_random_uuid(), 'CASE-2024-00001', 'CUST-001', 'Turkcell Subscriber A',
     'OPEN', 'HIGH', 'BILLING_DISPUTE',
     'Overcharge on July invoice',
     'Customer reports being charged twice for the same data package on 2024-07-01.',
     NOW() + INTERVAL '8 hours'),

    (gen_random_uuid(), 'CASE-2024-00002', 'CUST-002', 'Vodafone Business B',
     'IN_REVIEW', 'CRITICAL', 'FRAUD_ALERT',
     'Suspicious international calls detected',
     'SIM card registered to customer shows 200+ calls to premium numbers in 1 hour.',
     NOW() + INTERVAL '2 hours'),

    (gen_random_uuid(), 'CASE-2024-00003', 'CUST-003', 'Turk Telekom Subscriber C',
     'APPROVED', 'MEDIUM', 'PORT_REQUEST',
     'Number portability request to competitor',
     'Customer requests MNP (Mobile Number Portability). All docs verified.',
     NOW() + INTERVAL '24 hours');
