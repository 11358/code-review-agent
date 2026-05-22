# Performance Patterns Skill

Expert knowledge for detecting Java performance anti-patterns and optimization opportunities.

## Database Access Patterns

Check for:
- Queries inside loops (N+1 problem). Look for: for() { repository.findById() } or for() { jdbcTemplate.query() }
- JPA lazy loading without @BatchSize or JOIN FETCH in loops
- Missing pagination on potentially large queries
- SELECT * without column filtering

Fix: Use JOIN FETCH or @EntityGraph for eager loading. Use IN clauses or batch fetching. Always paginate list queries.

## Object Allocation

Check for:
- String concatenation in loops (use StringBuilder)
- Boxing/unboxing in hot paths (prefer primitive collections like IntArrayList)
- new Object() in tight loops (reuse instances or use object pools)
- Stream API with excessive intermediate object creation in performance-critical paths
- new BigDecimal("0.00") repeated (use BigDecimal.ZERO)

Fix: Use StringBuilder for loop concatenation. Prefer primitives. Reuse immutable constants.

## Collection Usage

Check for:
- ArrayList.contains() in a loop (O(n*m)). Use HashSet for membership checks.
- LinkedList used where ArrayList is better (LinkedList has poor cache locality)
- Vector or Hashtable (legacy, use ArrayList/HashMap or concurrent variants)
- Unbounded collections risking memory exhaustion

Fix: Choose the right data structure for the access pattern. Use Set for contains checks. Specify initial capacity for known-size collections.

## Caching Opportunities

Check for:
- Repeated calls to external services or databases with same parameters
- Expensive computation not memoized
- Pattern matching or regex compilation on each invocation (compile once, reuse)
- ResourceBundle/MessageFormat recreated per call (cache instances)

Fix: Use @Cacheable, Caffeine cache, or ConcurrentHashMap for memoization. Compile Pattern instances as static final fields.

## Concurrency Performance

Check for:
- synchronized methods on hot paths (consider ReadWriteLock or ConcurrentHashMap)
- Oversized critical sections (move non-shared code outside synchronized block)
- Thread.sleep() for waiting (use CountDownLatch, CompletableFuture, or ScheduledExecutor)
- Creating new Thread instead of using thread pool
- ForkJoinPool.commonPool() used for blocking operations

Fix: Minimize lock contention. Use non-blocking algorithms where possible. Always use ExecutorService.

## I/O Optimization

Check for:
- FileInputStream/FileOutputStream without BufferedInputStream/BufferedOutputStream
- Reading file byte-by-byte or line-by-line without buffer
- NIO channels not used for large file operations
- GZIPInputStream wrapping FileInputStream directly (double buffer hurts performance)

Fix: Always wrap streams with buffered variants. Use Files.readString/writeString for simple cases. Use NIO for large files.
