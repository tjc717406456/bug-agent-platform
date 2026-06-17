insert into project (name, code, description, created_at, updated_at)
select 'HRS Demo', 'hrs-demo', 'Sample project for bug analysis', now(), now()
where not exists (select 1 from project where code = 'hrs-demo');

insert into dbhub_datasource_config (datasource_key, host, port, username, password, database_name, created_at, updated_at)
select 'bug_agent', 'localhost', 3306, 'root', '1234', 'bug_agent', now(), now()
where not exists (select 1 from dbhub_datasource_config where datasource_key = 'bug_agent');

insert into dbhub_datasource_config (datasource_key, host, port, username, password, database_name, created_at, updated_at)
select 'user_bug_demo', 'localhost', 3306, 'root', '1234', 'user_bug_demo', now(), now()
where not exists (select 1 from dbhub_datasource_config where datasource_key = 'user_bug_demo');
