/// A database login. Shared by [CredentialsRepository] (storage) and
/// `QueryExecutionService`/connectors (consumption).
typedef DatabaseCredentials = ({String username, String password});

const emptyCredentials = (username: '', password: '');
