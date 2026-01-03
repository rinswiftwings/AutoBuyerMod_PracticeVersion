# AspectJ Basics - Hooking Into Game Events

This reference guide explains AspectJ fundamentals and how to create hooks that intercept game method calls.

## What is AspectJ?

AspectJ is a Java extension that enables **Aspect-Oriented Programming (AOP)**. It allows you to:
- Intercept method calls without modifying the original code
- Execute code before, after, or around method execution
- Add functionality to existing classes without changing them

**Why AspectJ for Space Haven mods?**
- Game code is compiled and not modifiable
- AspectJ lets you "hook into" game methods
- No need to modify game source code
- Works with decompiled game classes

## Core Concepts

### Aspect

An **aspect** is a class annotated with `@Aspect` that contains **advice** (code to execute) and **pointcuts** (where to execute it).

```java
@Aspect
public class MyAspect {
    // Advice methods go here
}
```

### Pointcut

A **pointcut** is an expression that matches method calls. It defines **where** your code should execute.

```java
@After("execution(* fi.bugbyte.spacehaven.world.World.setTradeDone(..))")
```

**Pointcut Syntax:**
- `execution(...)` - Matches method execution
- `*` - Wildcard (matches anything)
- `..` - Matches any number of parameters
- `args(...)` - Matches specific arguments

### Advice

**Advice** is the code that executes at the pointcut. Types:
- `@Before` - Executes before method
- `@After` - Executes after method (success or exception)
- `@Around` - Executes instead of method (can prevent execution)
- `@AfterReturning` - Executes only after successful return
- `@AfterThrowing` - Executes only after exception

## Basic Hook Example

```java
@Aspect
public class MyAspect {
    
    @After("execution(* fi.bugbyte.spacehaven.world.World.setTradeDone(..)) && args(trade) && this(world)")
    public void onTradeDone(Trading.TradeAgreement trade, World world) {
        // This code runs after World.setTradeDone() is called
        System.out.println("Trade completed: " + trade.id);
    }
}
```

**Breaking it down:**
- `@After` - Execute after the method
- `execution(...)` - Match method execution
- `*` - Any return type
- `fi.bugbyte.spacehaven.world.World.setTradeDone(..)` - Method signature
- `args(trade)` - Capture the trade argument
- `this(world)` - Capture the World instance
- Method parameters match captured values

## Pointcut Expressions

### Method Signature Matching

```java
// Match any method in World class
execution(* fi.bugbyte.spacehaven.world.World.*(..))

// Match specific method
execution(* fi.bugbyte.spacehaven.world.World.setTradeDone(..))

// Match methods with specific return type
execution(void fi.bugbyte.spacehaven.world.World.*(..))

// Match methods with specific parameters
execution(* fi.bugbyte.spacehaven.world.World.addShip(Ship))
```

### Argument Capturing

```java
// Capture first argument
@After("execution(* World.method(Ship)) && args(ship)")
public void hook(Ship ship) { }

// Capture multiple arguments
@After("execution(* World.method(Ship, int)) && args(ship, count)")
public void hook(Ship ship, int count) { }

// Capture 'this' object
@After("execution(* World.method(..)) && this(world)")
public void hook(World world) { }

// Capture both 'this' and arguments
@After("execution(* World.method(Ship)) && args(ship) && this(world)")
public void hook(Ship ship, World world) { }
```

### Combining Conditions

```java
// Multiple conditions with &&
@After("execution(* World.method(..)) && args(arg) && this(world) && target(obj)")

// OR conditions with ||
@After("execution(* World.method1(..)) || execution(* World.method2(..))")
```

## Advice Types

### @Before

Executes **before** the method runs. Cannot prevent execution, but can modify arguments (with @Around).

```java
@Before("execution(* World.method(..))")
public void beforeMethod() {
    System.out.println("About to call method");
}
```

### @After

Executes **after** the method completes (success or exception). Most common for Space Haven mods.

```java
@After("execution(* World.setTradeDone(..)) && args(trade) && this(world)")
public void afterTradeDone(Trading.TradeAgreement trade, World world) {
    // This runs whether method succeeded or threw exception
    handleTradeCompletion(trade, world);
}
```

### @AfterReturning

Executes only after **successful** return. Can access return value.

```java
@AfterReturning(pointcut = "execution(* World.getShip(..))", returning = "ship")
public void afterGetShip(Ship ship) {
    if (ship != null) {
        System.out.println("Got ship: " + ship.getName());
    }
}
```

### @AfterThrowing

Executes only after **exception** is thrown. Can access exception.

```java
@AfterThrowing(pointcut = "execution(* World.method(..))", throwing = "ex")
public void afterException(Exception ex) {
    System.err.println("Exception occurred: " + ex.getMessage());
}
```

### @Around

Executes **instead of** the method. Can prevent execution or modify behavior.

```java
@Around("execution(* World.method(..))")
public Object aroundMethod(ProceedingJoinPoint joinPoint) throws Throwable {
    // Code before method
    System.out.println("Before method");
    
    // Call original method (or skip it)
    Object result = joinPoint.proceed();
    
    // Code after method
    System.out.println("After method");
    
    return result;
}
```

**Use @Around carefully:**
- Can break game functionality if used incorrectly
- Use @After or @Before when possible
- Only use @Around when you need to prevent/modify execution

## Common Patterns

### Pattern 1: Trade Completion Hook

```java
@After("execution(* fi.bugbyte.spacehaven.world.World.setTradeDone(..)) && args(trade) && this(world)")
public void onTradeDone(Trading.TradeAgreement trade, World world) {
    if (trade != null && trade.isPlayerTrade()) {
        // Handle player trade completion
        handleTradeCompletion(trade, world);
    }
}
```

### Pattern 2: Ship Added Hook

```java
@After("execution(* fi.bugbyte.spacehaven.world.World.addShip(..)) && args(ship) && this(world)")
public void onShipAdded(Ship ship, World world) {
    if (ship != null && !ship.isPlayerShip()) {
        // Handle NPC ship arrival
        handleNewShip(ship, world);
    }
}
```

### Pattern 3: Ship Jumped Hook

```java
@After("execution(* fi.bugbyte.spacehaven.world.World.shipJumped(..)) && args(ship) && this(world)")
public void onShipJumped(Ship ship, World world) {
    if (ship != null) {
        // Clean up when ship leaves
        cleanupShipState(ship.getShipId());
    }
}
```

## Registering Aspects

Aspects must be registered in `aop.xml`:

```xml
<aspectj>
    <aspects>
        <aspect name="com.rinswiftwings.autobuyermod.AutoBuyerAspect"/>
    </aspects>
    <weaver>
        <include within="fi.bugbyte..*" />
        <include within="com.rinswiftwings..*" />
    </weaver>
</aspectj>
```

**Why registration is needed:**
- Tells AspectJ which classes are aspects
- Specifies which packages can be woven
- Enables AspectJ to find and apply aspects

## Finding Game Methods to Hook

1. **Decompile spacehaven.jar** using JD-GUI or similar
2. **Search for classes** you need (e.g., `World`, `Ship`, `Trading`)
3. **Find methods** that fire at the right time
4. **Check method signatures** (parameters, return types)
5. **Test hooks** with logging to verify they fire

**Example search process:**
- Need to detect trade completion → Search for `TradeAgreement` or `setTradeDone`
- Need to detect ship arrival → Search for `addShip` or `Ship` constructor
- Need to detect entity boarding → Search for `entityBoarded` or `Entity`

## Best Practices

### 1. Always Check for Null

```java
@After("execution(* World.method(..)) && args(arg) && this(world)")
public void hook(Object arg, World world) {
    if (arg == null || world == null) {
        return; // Early exit
    }
    // Safe to use arg and world
}
```

### 2. Use Try-Catch in Hooks

```java
@After("execution(* World.method(..))")
public void hook() {
    try {
        // Your code
    } catch (Exception e) {
        // Log error, don't crash game
        ModLog.log("Error in hook: " + e.getMessage());
    }
}
```

### 3. Filter Unwanted Calls

```java
@After("execution(* World.setTradeDone(..)) && args(trade) && this(world)")
public void onTradeDone(Trading.TradeAgreement trade, World world) {
    // Only process player trades
    if (trade == null || !trade.isPlayerTrade()) {
        return;
    }
    // Process player trade
}
```

### 4. Avoid Expensive Operations

```java
@After("execution(* World.method(..))")
public void hook() {
    // BAD: Expensive operation on every call
    // processAllShips(); 
    
    // GOOD: Quick check, defer expensive work
    if (shouldProcess()) {
        scheduleProcessing(); // Do work later
    }
}
```

## Debugging Hooks

### Enable Verbose Logging

In `aop.xml`:
```xml
<weaver options="-verbose -showWeaveInfo">
```

This shows which methods are being woven.

### Test with Logging

```java
@After("execution(* World.method(..))")
public void hook() {
    ModLog.log("Hook fired!"); // Verify hook is working
}
```

### Check Method Signatures

Pointcuts must match method signatures exactly:
- Package name must match
- Class name must match
- Method name must match
- Parameter types must match (or use `..`)

## Common Mistakes

### Mistake 1: Wrong Package Name

```java
// WRONG: Package doesn't match
@After("execution(* spacehaven.World.method(..))")

// CORRECT: Full package path
@After("execution(* fi.bugbyte.spacehaven.world.World.method(..))")
```

### Mistake 2: Wrong Method Signature

```java
// WRONG: Method signature doesn't match
@After("execution(* World.method(int))") // Game method takes Ship

// CORRECT: Match actual signature or use wildcard
@After("execution(* World.method(..))") // Matches any parameters
```

### Mistake 3: Aspect Not Registered

```java
// Aspect class exists but not in aop.xml
// Solution: Add to aop.xml <aspects> section
```

## Next Steps

- Review **05_Game_Hooks_Used.md** for real examples from AutoBuyerMod
- Study **04_Decompiled_Research_Files.md** to find game classes
- Read **07_Code_Walkthrough.md** to see hooks in context

---

**Previous:** [02_Project_Structure.md](02_Project_Structure.md)  
**Next:** [04_Decompiled_Research_Files.md](04_Decompiled_Research_Files.md)

