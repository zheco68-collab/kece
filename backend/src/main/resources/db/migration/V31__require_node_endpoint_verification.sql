-- A previously verified runtime did not prove ownership of its configured HTTPS endpoint.
UPDATE judge_node_check c
JOIN judge_node n ON n.id = c.node_id
SET c.state = 'EXPIRED'
WHERE n.base_url IS NOT NULL AND c.state IN ('QUEUED', 'RUNNING');

UPDATE judge_node SET verified = FALSE WHERE base_url IS NOT NULL;
