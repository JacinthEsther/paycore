CREATE ROLE paycore_owner LOGIN PASSWORD :owner_password;
CREATE ROLE paycore_app LOGIN PASSWORD :app_password NOINHERIT;

GRANT CREATE, USAGE ON SCHEMA public TO paycore_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

