-- Weighted simple-config vector keeps short and symbolic terms searchable (Go, AI, C#).
ALTER TABLE job_applications ADD COLUMN assistant_search_vector tsvector GENERATED ALWAYS AS (
 setweight(to_tsvector('simple', coalesce(vacancy_name, '')), 'A') ||
 setweight(to_tsvector('simple', coalesce(organization, '')), 'A') ||
 setweight(to_tsvector('simple', coalesce(recruiter_name, '')), 'B') ||
 setweight(to_tsvector('simple', coalesce(note, '')), 'C')
) STORED;
CREATE INDEX ft_job_applications_assistant ON job_applications USING GIN (assistant_search_vector);
