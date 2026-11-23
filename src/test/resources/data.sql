INSERT INTO application_statuses (name, display_order) VALUES
('RH', 1),
('Pending HR Response', 2),
('Pending Hiring Manager Response', 3),
('Technical Test', 4),
('Pending Technical Test Response', 5),
('Offer Negotiation', 6),
('Ghosting', 7),
('Rejected', 8),
('Approved', 9);

INSERT INTO assistant_quota_locks (lock_name) VALUES ('assistant-global');
