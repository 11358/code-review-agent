# Security Rules Skill

Expert knowledge for detecting OWASP Top 10 security vulnerabilities in Java code.

## SQL Injection Prevention

Check for:
- String concatenation in SQL queries (Statement, PreparedStatement misuse)
- MyBatis `${}` interpolation (unsafe) vs `#{}` (safe)
- JPA native queries with string concatenation
- Dynamic SQL built with StringBuilder/String.format

Fix: Always use parameterized queries. For dynamic queries, use Criteria API or a query builder that enforces parameterization.

## XSS Prevention

Check for:
- Unescaped user input written to HTTP response
- `@ResponseBody` methods returning user-controlled strings without sanitization
- Template engines with auto-escaping disabled

Fix: Enable auto-escaping in Thymeleaf/other templates. Use OWASP Java HTML Sanitizer for rich text input.

## Path Traversal

Check for:
- File operations using request parameters as path components
- Zip Slip: extracting archives without validating entry paths
- File(path).getCanonicalPath() not checked against base directory

Fix: Validate paths against a whitelist. Use getCanonicalPath() and verify it starts with the allowed base directory.

## Command Injection

Check for:
- Runtime.getRuntime().exec() with user input
- ProcessBuilder with unsanitized arguments
- Shell execution (`/bin/sh -c`) with concatenated commands

Fix: Avoid shell execution. Use ProcessBuilder with argument list (not string). Never pass user input directly.

## Sensitive Data Exposure

Check for:
- Passwords/tokens/secrets in log statements
- PII (phone, ID card, email) in log or error messages
- Hardcoded credentials or API keys
- Sensitive data in toString() or serialized output

Fix: Use parameterized logging (log.info("user: {}", mask(user))). Externalize secrets with env vars or vault.

## Insecure Deserialization

Check for:
- ObjectInputStream.readObject() without validation
- Java serialization accepting untrusted data
- Libraries with known deserialization gadgets (Commons Collections, etc.)

Fix: Use JSON/Protobuf instead of Java serialization. If unavoidable, use a whitelist-based ObjectInputStream.

## Auth/Authz Bypass

Check for:
- Missing @PreAuthorize or @Secured on endpoints
- Direct object references without ownership check
- JWT without signature verification or expiration check

Fix: Add Spring Security method security. Verify resource ownership in service layer.
