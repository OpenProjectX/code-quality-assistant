# Code Review — 3953d4e^1 → 3953d4e (Java exercise files)

> Prompt used: `prompts/diff-review.md` (updated — table format in §4, deleted-file guidance in §2)

---

## 1. Summary

This commit adds four standalone algorithm exercise files to `com.example.demo`, covering linked-list reversal (two independent implementations) and a multi-threaded character-printing coordination exercise. The files are coding-practice work rather than production features. Several correctness bugs and an incomplete skeleton are present and should be resolved before treating these as reference material.

- `ConcurrentCache.java` — Empty cache skeleton; `main` contains an unrelated Unicode string experiment
- `Main_478307.java` — Iterative linked-list reversal with a minor output-ordering bug
- `Solution_12347894.java` — Second linked-list reversal via custom generic `ListNode`; dummy-head initialisation bug and shadowed type parameter
- `ThreadPrint.java` — Three-thread character-printing exercise; doubles the intended thread count and risks deadlock

**Risk Level**: Low — All four files are isolated exercises with their own `main` methods and touch no shared infrastructure, production business logic, or tests.

---

## 2. What Changed

Four new Java source files were added to `src/main/java/com/example/demo`. No files were deleted or modified.

- **`ConcurrentCache.java`** — New file. Declares a `ConcurrentCache<K,V>` class and two interfaces (`Cache<K,V>`, `LoadingFunction<K,V>`). The class body is entirely empty — it does not implement its own `Cache` interface. A `main` method probes Unicode string/char encoding as an unrelated side-experiment. Clearly an incomplete skeleton.
- **`Main_478307.java`** — New file. A self-contained iterative linked-list reversal exercise. Builds a 5-node list (1→2→3→4→5) and reverses it with a canonical three-pointer loop. Output logic has a minor ordering bug.
- **`Solution_12347894.java`** — New file. A second linked-list reversal approach using a generic inner class `ListNode<Integer>` with `append`/`prepend` helpers. The `reverseList` method builds a new reversed list using a dummy head, which introduces a spurious trailing null node. The generic type parameter is named `Integer`, shadowing `java.lang.Integer`.
- **`ThreadPrint.java`** — New file. A multi-threaded printing exercise: three intended worker threads coordinate via a `ReentrantLock` and three `Condition` objects to print characters from a shared queue in round-robin order. Six workers are actually submitted due to copy-paste duplication. The executor is never shut down.

---

## 3. Code Quality Assessment

### Correctness

Correctness problems exist in three of the four files (detailed in §4). `Main_478307`'s reversal algorithm is correct but output order is wrong. `Solution_12347894`'s `reverseList` produces a list with a spurious trailing null node from the dummy-head initialisation. `ThreadPrint` creates twice the intended number of threads, breaking the round-robin invariant, and its signal-before-await sequencing is fragile enough to deadlock.

### Design

All four files land in `com.example.demo` directly rather than a dedicated sub-package (e.g., `exercises`). `ConcurrentCache` mixes unrelated concerns: the interface hierarchy implies a caching abstraction while the `main` body tests string encoding. `Solution_12347894.reverseList` allocates one new `ListNode` per iteration — unnecessary heap pressure; the canonical O(1)-space reversal mutates existing node pointers in place.

### Idioms & Conventions

- `Solution_12347894.java:34`: Generic type parameter named `Integer` shadows `java.lang.Integer`. Legal but deeply misleading — any reference to `java.lang.Integer` methods inside this class requires full qualification.
- `ConcurrentCache.java:3–5`: Three imports (`CompletableFuture`, `ConcurrentHashMap`, `AtomicReference`) are unused, indicating planned but absent implementation.
- `ThreadPrint.java:6`: `Executor` is imported but unused; `ExecutorService` is used everywhere.
- `ThreadPrint.java`: The `try/finally` pattern that always calls `lock.unlock()` correctly follows the `java.util.concurrent.locks` idiom. `Thread.onSpinWait()` in the busy-wait loop appropriately signals the CPU.
- Missing newline at end of file in `ConcurrentCache.java`, `Main_478307.java`, and `ThreadPrint.java`.

### Readability

- `Solution_12347894.java:5`: Variable `listNodeListNode` is a doubled-word name with no semantic meaning; `prev` or `reversed` would be clearer.
- `Main_478307.java:27`: `System.out.print("null ")` before the traversal loop makes output semantically backwards.
- `ThreadPrint.java:26–28`: The `future1/2/3` submissions are copy-paste artifacts with identical arguments, obscuring the intended three-thread design.

### Completeness

`ConcurrentCache.java` declares a `Cache<K,V>` interface that the class itself does not implement. The body is empty except for the unrelated `main` — an incomplete skeleton. The three unused imports further suggest planned but unimplemented functionality.

---

## 4. Potential Issues

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| 1 | `ThreadPrint.java:26–28` | `future1`, `future2`, `future3` all submit identical args `(conditionMain, condition_3, condition_1)`, duplicating thread 3. Six workers compete on a 3-slot round-robin: multiple threads wake per signal, breaking ordering and causing duplicate or missing output. | High |
| 2 | `ThreadPrint.java:32–40` | Potential deadlock: six workers race to call `conditionMain.signal()` while the main thread may only be in `await()` once. Excess signals are silently dropped; if the signal chain breaks, all threads stall indefinitely. | High |
| 3 | `ThreadPrint.java:38` | `future1.isDone()` is called as a statement; its return value is discarded — it does not block or cancel. The executor is never shut down, so the JVM may run indefinitely. | High |
| 4 | `Solution_12347894.java:5` | `reverseList` initialises `listNodeListNode = new ListNode<>()` with `val = null`. The returned list always carries a trailing null-valued node, producing incorrect output. | High |
| 5 | `Solution_12347894.java:34` | Generic type parameter named `Integer` shadows `java.lang.Integer`. Autoboxing and `java.lang.Integer` static methods silently fail to resolve within this class. | Medium |
| 6 | `Main_478307.java:27` | `System.out.print("null ")` is printed before the loop. Output is `null 5 4 3 2 1` instead of the expected `5 4 3 2 1 null`. | Low |
| 7 | `ConcurrentCache.java:3–5` | Three unused imports indicate planned implementation that was never added. | Low |

---

## 5. Suggestions for Improvement

**`ThreadPrint.java` — remove duplicate thread submissions (lines 26–28)**

Delete the three `future1/2/3` `submit` calls; keep only the three `execute` calls with the correct distinct condition chains. Shut down the executor to allow JVM exit:

```java
executor.execute(() -> printOnConditions(conditionMain, condition_1, condition_2));
executor.execute(() -> printOnConditions(conditionMain, condition_2, condition_3));
executor.execute(() -> printOnConditions(conditionMain, condition_3, condition_1));
executor.shutdown();
executor.awaitTermination(10, TimeUnit.SECONDS);
```

**`ThreadPrint.java` — replace `conditionMain` await with a `CountDownLatch`**

The current design races: a worker can signal `conditionMain` before the main thread reaches `await()`. A `CountDownLatch` eliminates this race entirely:

```java
CountDownLatch ready = new CountDownLatch(3);
// inside printOnConditions, after acquiring the lock:
ready.countDown();
// in main, replace the lock.lock()/conditionMain.await() block:
ready.await();
condition_1.signal(); // kick off the first worker
```

**`Solution_12347894.java` — fix `reverseList` to use in-place pointer reversal (line 5)**

The dummy-head bug and the unnecessary per-iteration allocation are both fixed by reversing in place:

```java
public static ListNode<Integer> reverseList(ListNode<Integer> head) {
    ListNode<Integer> prev = null;
    while (head != null) {
        ListNode<Integer> next = head.next; // save forward pointer
        head.next = prev;                   // reverse the link
        prev = head;
        head = next;
    }
    return prev;
}
```

**`Solution_12347894.java` — rename the generic type parameter (line 34)**

```java
public static class ListNode<T> {
    T val;
    ListNode<T> next;
    // ...
}
```

**`Main_478307.java` — move `"null"` print after the loop (line 27)**

```java
while (reversed != null) {
    System.out.print(reversed.val + " ");
    reversed = reversed.next;
}
System.out.println("null");
```

**`ConcurrentCache.java` — implement or stub the interface; remove unused imports**

Either add a `ConcurrentHashMap`-backed implementation of `Cache<K,V>`, or remove the placeholder interfaces and dead imports until ready.

---

## 6. Positive Observations

- **`Main_478307.java:33–43` — canonical iterative reversal**: The `reverseNodeList` algorithm is the standard O(n)-time O(1)-space pointer-swap reversal. Logic is concise and correct.

- **`ThreadPrint.java` — correct `ReentrantLock` + `Condition` idiom**: The `try/finally` that always calls `lock.unlock()` follows the recommended pattern. Using `Thread.onSpinWait()` in the busy-wait loop is a good touch that signals CPU spin state.

- **`Solution_12347894.java` — `append`/`prepend` helpers**: Factoring pointer manipulation into named methods on `ListNode` improves readability over raw `node.next = ...` assignments inline.
