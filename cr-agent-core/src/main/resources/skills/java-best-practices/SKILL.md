# Java Best Practices Skill

Expert knowledge for detecting common Java bugs, anti-patterns, and correctness issues.

## Null Safety

Check for:
- Method calls on potentially null references without prior null check
- Optional.get() without isPresent() check
- @Nullable return values used without null handling
- Autoboxing null values (Integer i = null; int x = i; // NPE)

Fix: Use Optional properly (orElse/orElseGet/orElseThrow). Add Objects.requireNonNull() for constructor parameters. Use @NonNull annotations.

## Resource Management

Check for:
- InputStream/OutputStream not closed (especially in exception paths)
- JDBC Connection/Statement/ResultSet not in try-with-resources
- HttpClient, RestTemplate not released
- FileChannel, RandomAccessFile left open

Fix: Always use try-with-resources. For resources that can't use try-with-resources, ensure finally block with null check.

## Error Handling

Check for:
- Empty catch blocks (silently swallowing exceptions)
- catch (Exception e) { e.printStackTrace(); } // Lost in production
- Catching Throwable (catches Errors like OutOfMemoryError)
- Throwing Exception instead of specific exception types
- Lost exception chain: throw new MyException(e.getMessage()) instead of throw new MyException(e)

Fix: Log exceptions with context. Chain exceptions properly. Use specific exception types.

## Concurrency

Check for:
- Shared mutable state without synchronization
- Double-checked locking on non-volatile field
- ConcurrentHashMap used incorrectly (e.g., check-then-act without atomic methods)
- Unsafe publication: starting thread in constructor before object fully initialized
- HashMap in multi-threaded context (use ConcurrentHashMap)

Fix: Use concurrent collections. Prefer volatile + AtomicReference/AtomicInteger. Use synchronized blocks or Lock.

## Common Pitfalls

Check for:
- equals() without hashCode() override (or vice versa)
- BigDecimal(double) constructor (loses precision, use BigDecimal(String))
- Comparing boxed primitives with == (use .equals())
- String.split() with regex-special characters
- SimpleDateFormat in multi-threaded context (use DateTimeFormatter)
- Float/Double for monetary calculations (use BigDecimal)
