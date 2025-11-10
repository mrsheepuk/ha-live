# ToolExecutor Interface Refactoring

## Overview
Introduced `ToolExecutor` interface to decouple session preparation from concrete tool execution implementation. This enables safe testing, mocking, and flexible implementations without modifying core logic.

## Motivation: The "House Lizard" Problem 🦎

**Problem:** Testing profiles, prompts, and conversation flows requires executing tools against Home Assistant, which can have unintended side effects:
- Turning on lights at 3am during testing
- Unlocking doors during development
- Triggering automations accidentally
- Adjusting thermostats unintentionally

**Solution:** Depend on an abstraction (`ToolExecutor`) instead of concrete implementation (`GeminiMCPToolExecutor`), allowing safe mock implementations for testing.

## Files Changed/Created

### New Files

#### 1. `services/ToolExecutor.kt` (75 lines)
**Purpose:** Interface defining the contract for executing Home Assistant tools

**Key Features:**
- Single method: `suspend fun executeTool(call: FunctionCallPart): FunctionResponsePart`
- Comprehensive KDoc explaining Dependency Inversion Principle
- Documents use cases: production, testing, simulation, logging, safe mode
- Clean abstraction for Gemini → Home Assistant tool execution

**Benefits Documented:**
- Testability without real HA APIs
- Flexibility for multiple implementations
- Separation of concerns
- Dependency inversion principle adherence

#### 2. `services/MockToolExecutor.kt` (136 lines)
**Purpose:** Safe mock implementation for testing without affecting real Home Assistant state

**Key Features:**
- Returns canned success responses
- Never makes actual calls to Home Assistant
- Thread-safe call tracking for debugging
- Comprehensive utility methods

**Companion Object API:**
```kotlin
MockToolExecutor.getCalledTools(): List<String>
MockToolExecutor.getCallCount(): Int
MockToolExecutor.wasToolCalled(toolName: String): Boolean
MockToolExecutor.clearCallHistory()
```

**Use Cases:**
- Profile testing without live HA instance
- Conversation flow validation
- Prompt development and iteration
- Feature testing without side effects
- Demo mode for showcasing the app

### Modified Files

#### 3. `services/GeminiMCPToolExecutor.kt`
**Changes:**
- Added `import uk.co.mrsheep.halive.services.ToolExecutor`
- Updated class declaration: `class GeminiMCPToolExecutor(...) : ToolExecutor`
- Added `override` modifier to `executeTool()` method
- **No implementation changes** - purely structural

#### 4. `services/GeminiSessionPreparer.kt`
**Changes:**
- Added `import uk.co.mrsheep.halive.services.ToolExecutor`
- Changed constructor parameter from `GeminiMCPToolExecutor` to `ToolExecutor`
- **No other changes** - existing usage works with interface

#### 5. `HAGeminiApp.kt`
**Changes:**
- Added `import uk.co.mrsheep.halive.services.ToolExecutor`
- Changed property type: `var toolExecutor: ToolExecutor? = null`
- Instantiation unchanged: still creates `GeminiMCPToolExecutor` (concrete class)
- **Critical fix:** Ensures app container uses interface type, not concrete type

## Architecture Improvements

### Before (Concrete Dependency)
```kotlin
class GeminiSessionPreparer(
    private val toolExecutor: GeminiMCPToolExecutor  // ❌ Concrete
)

class HAGeminiApp {
    var toolExecutor: GeminiMCPToolExecutor? = null  // ❌ Concrete
}
```

### After (Interface Dependency)
```kotlin
class GeminiSessionPreparer(
    private val toolExecutor: ToolExecutor  // ✅ Interface
)

class HAGeminiApp {
    var toolExecutor: ToolExecutor? = null  // ✅ Interface
}
```

### Dependency Inversion Principle
**Before:** High-level modules (GeminiSessionPreparer) depended on low-level modules (GeminiMCPToolExecutor)

**After:** Both depend on abstraction (ToolExecutor)

```
       ┌──────────────────────┐
       │   ToolExecutor       │  (Interface)
       │  - executeTool()     │
       └──────────────────────┘
                 △
                 │ implements
       ┌─────────┴──────────────────────┐
       │                                 │
┌──────────────────┐          ┌─────────────────┐
│ GeminiMCPTool    │          │ MockToolExecutor │
│ Executor         │          │                  │
└──────────────────┘          └──────────────────┘
  (Production)                   (Testing)
```

## Usage Examples

### Production Use (Unchanged)
```kotlin
// HAGeminiApp.kt
toolExecutor = GeminiMCPToolExecutor(mcpClient!!)

// MainViewModel.kt
sessionPreparer = GeminiSessionPreparer(
    mcpClient = app.mcpClient!!,
    haApiClient = app.haApiClient!!,
    toolExecutor = app.toolExecutor!!,  // Works! Interface type
    onLogEntry = ::addToolLog
)
```

### Test Mode (New Capability)
```kotlin
// For safe testing without affecting real HA state
val mockExecutor = MockToolExecutor()

val preparer = GeminiSessionPreparer(
    mcpClient = mockMcpClient,
    haApiClient = mockHaApiClient,
    toolExecutor = mockExecutor,  // ✅ Safe mock
    onLogEntry = { log -> println(log) }
)

// After testing
println("Tools called: ${MockToolExecutor.getCalledTools()}")
// Output: [turn_on_light, set_temperature, get_weather]

MockToolExecutor.clearCallHistory()  // Reset for next test
```

### Future: Profile Test Mode UI
```kotlin
// Future enhancement: Toggle in profile settings
if (profile.testMode) {
    app.toolExecutor = MockToolExecutor()
} else {
    app.toolExecutor = GeminiMCPToolExecutor(mcpClient!!)
}
```

## Benefits Achieved

### ✅ Safety
- Users can test profiles without affecting real Home Assistant state
- No accidental service calls during development
- Safe demo mode for showcasing features

### ✅ Testability
- Easy to unit test session preparation logic
- Mock implementations for integration tests
- Verify which tools Gemini attempts to call

### ✅ Flexibility
- Swap implementations without changing calling code
- Future implementations: logging wrapper, rate limiter, validator
- A/B testing with different execution strategies

### ✅ Maintainability
- Clear separation between interface and implementation
- Single Responsibility Principle: each implementation handles one concern
- Easy to add new implementations (e.g., `SimulatedToolExecutor`)

### ✅ Backward Compatibility
- Existing code continues to work unchanged
- MainViewModel requires no modifications
- Production behavior identical to before

## Testing Recommendations

### Unit Tests
```kotlin
class GeminiSessionPreparerTest {
    @Test
    fun `prepareAndInitialize uses injected executor`() {
        val mockExecutor = MockToolExecutor()
        val preparer = GeminiSessionPreparer(
            mcpClient, haApiClient, mockExecutor, logger
        )

        preparer.prepareAndInitialize(profile, geminiService, defaultPrompt)

        // Verify mock was used, not real executor
        assertTrue(MockToolExecutor.getCallCount() > 0)
    }
}
```

### Integration Tests
```kotlin
@Test
fun `profile with live context uses tool executor`() {
    val mockExecutor = MockToolExecutor()
    val profile = Profile(includeLiveContext = true)

    preparer.prepareAndInitialize(profile, service, prompt)

    // Verify GetLiveContext was called
    assertTrue(MockToolExecutor.wasToolCalled("GetLiveContext"))
}
```

## Future Enhancements

### 1. Logging Wrapper
```kotlin
class LoggingToolExecutor(
    private val delegate: ToolExecutor
) : ToolExecutor {
    override suspend fun executeTool(call: FunctionCallPart): FunctionResponsePart {
        Log.d("ToolExecution", "Calling ${call.name} with ${call.args}")
        val result = delegate.executeTool(call)
        Log.d("ToolExecution", "Result: ${result.response}")
        return result
    }
}
```

### 2. Rate Limiting Wrapper
```kotlin
class RateLimitedToolExecutor(
    private val delegate: ToolExecutor,
    private val maxCallsPerMinute: Int
) : ToolExecutor {
    // Implementation with rate limiting logic
}
```

### 3. Validation Wrapper
```kotlin
class ValidatingToolExecutor(
    private val delegate: ToolExecutor,
    private val dangerousTools: Set<String>
) : ToolExecutor {
    override suspend fun executeTool(call: FunctionCallPart): FunctionResponsePart {
        if (call.name in dangerousTools && !userConfirmed) {
            return errorResponse("Tool requires confirmation")
        }
        return delegate.executeTool(call)
    }
}
```

### 4. Simulated Executor
```kotlin
class SimulatedToolExecutor(
    private val responseMap: Map<String, String>
) : ToolExecutor {
    // Returns realistic simulated responses based on tool name
}
```

### 5. Profile Test Mode (UI Feature)
Add to Profile settings:
- Checkbox: "Test mode (simulate tool execution)"
- When enabled, app uses `MockToolExecutor` instead of real executor
- Safe way to test prompts and conversation flows

## Code Quality Metrics

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Coupling | High | Low | ⬇️ |
| Testability | Medium | High | ⬆️ |
| Flexibility | Low | High | ⬆️ |
| SOLID Compliance | Partial | Full | ⬆️ |
| Safety | Medium | High | ⬆️ |

## Conclusion

This refactoring successfully:
- ✅ Introduced clean interface for tool execution
- ✅ Decoupled session preparation from concrete implementation
- ✅ Enabled safe testing without affecting real Home Assistant state
- ✅ Provided mock implementation with debugging utilities
- ✅ Fixed critical type issue in HAGeminiApp (used concrete type)
- ✅ Maintained 100% backward compatibility
- ✅ Followed Dependency Inversion Principle
- ✅ Opened door for future enhancements (logging, rate limiting, validation)

**No breaking changes.** All existing code continues to work identically.

**Future potential:** Profile test mode UI feature to let users safely experiment with prompts and conversation flows without worrying about unintended side effects on their smart home.
