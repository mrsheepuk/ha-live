# **Task 2: Gemini-to-HA Executor**

## **1. Objective**

To build the "Glue Task #2" module. This module's responsibility is to act as the "hands" of the assistant. It will receive a function_call request from the Gemini Live API and "execute" it by making the corresponding API call to the Home Assistant server.

It then takes the result of that action (e.g., "success" or "error: entity not found") and reports it back to the Gemini session, closing the conversational loop.

## **2. Input: Gemini API FunctionCallPart**

This is the object we will receive from the Gemini Live API when it wants to perform an action.

### **Example FunctionCallPart (Kotlin/SDK)**

// This object is provided by the Gemini Live API callback  
val functionCall: FunctionCallPart = FunctionCallPart(  
    name = "light.turn_on", // This is the key  
    args = mapOf(  
        "entity_id" to "light.kitchen_main",  
        "brightness_pct" to 75  
    )  
)

## **3. Output: Gemini API FunctionResponsePart**

This is the object we must construct and send *back* to the Gemini Live API to inform it of the tool's result.

### **Example FunctionResponsePart (Kotlin/SDK)**

// We will build this object:  
val functionResponse: FunctionResponsePart = FunctionResponsePart(  
    name = "light.turn_on", // Must match the input name  
    response = JsonObject(  
        mapOf(  
            "status" to JsonPrimitive("success"),  
            "message" to JsonPrimitive("The light.kitchen_main was turned on successfully.")  
        )  
    )  
)

## **4. Target: Home Assistant REST API**

We will use **one primary Home Assistant API endpoint** for almost all actions:

* **Endpoint:** POST /api/services/<domain>/<service>  
* **Auth:** Authorization: Bearer <YOUR_LONG_LIVED_TOKEN>  
* **Body:** A JSON object containing the service data.

### **Mapping Input to Target**

This is the core of the "glue" logic:

1. The Gemini functionCall.name (e.g., "light.turn_on") is parsed.  
2. We split it by the . character:  
   * domain = "light"  
   * service = "turn_on"  
3. We construct the HA API path: /api/services/light/turn_on.  
4. The Gemini functionCall.args (e.g., mapOf("entity_id" to "light.kitchen_main", ...)  
5. This map is serialized to a JSON object: {"entity_id": "light.kitchen_main", "brightness_pct": 75}.  
6. This JSON becomes the **body** of our POST request.

This 1:1 mapping is perfect. The tool definitions we provide in Task 1 (from MCP) directly match the service-call structure HA expects.

## **5. Implementation (Pseudocode)**

This logic will live inside the HomeAssistantRepository class we defined in Task 0.

// In HomeAssistantRepository.kt  
// (Assumes Retrofit setup)

// 1. Define the Retrofit/Ktor API interface  
interface HomeAssistantApi {  
    @POST("/api/services/{domain}/{service}")  
    @Headers("Content-Type: application/json")  
    suspend fun callService(  
        @Header("Authorization") token: String,  
        @Path("domain") domain: String,  
        @Path("service") service: String,  
        @Body body: Map<String, @JvmSuppressWildcards Any> // Use Map to send dynamic JSON  
    ): Response<JsonElement> // Receive a generic JSON response  
}

// 2. Implemenation in the Repository  
class HomeAssistantRepository(private val api: HomeAssistantApi) {  
      
    private val haToken = "Bearer YOUR_LONG_LIVED_TOKEN" // This should be stored securely

    /**  
     * This is the main function for Task 2.  
     * It receives a call from Gemini and executes it on Home Assistant.  
     */  
    suspend fun executeTool(functionCall: FunctionCallPart): FunctionResponsePart {  
        val (domain, service) = parseFunctionName(functionCall.name)  
          
        if (domain == null || service == null) {  
            return createErrorResponse(functionCall, "Invalid function name format. Expected 'domain.service'.")  
        }

        return try {  
            // 3. Make the actual network call  
            val response = api.callService(  
                token = haToken,  
                domain = domain,  
                service = service,  
                body = functionCall.args  
            )

            // 4. Handle HA's response  
            if (response.isSuccessful) {  
                // Success!  
                createSuccessResponse(functionCall, response.body())  
            } else {  
                // HA returned an error (e.g., 400, 404)  
                createErrorResponse(functionCall, "HA Error ${response.code()}: ${response.errorBody()?.string()}")  
            }  
        } catch (e: Exception) {  
            // Network error, etc.  
            createErrorResponse(functionCall, "Network Error: ${e.message}")  
        }  
    }

    // Helper to split "light.turn_on" into ("light", "turn_on")  
    private fun parseFunctionName(name: String): Pair<String?, String?> {  
        val parts = name.split(".", limit = 2)  
        return if (parts.size == 2) Pair(parts[0], parts[1]) else Pair(null, null)  
    }

    // Helper to build a success response for Gemini  
    private fun createSuccessResponse(call: FunctionCallPart, haResponse: JsonElement?): FunctionResponsePart {  
        return FunctionResponsePart(  
            name = call.name,  
            response = JsonObject(  
                mapOf(  
                    "status" to JsonPrimitive("success"),  
                    "ha_response" to (haResponse ?: JsonNull)  
                )  
            )  
        )  
    }

    // Helper to build an error response for Gemini  
    private fun createErrorResponse(call: FunctionCallPart, errorMessage: String): FunctionResponsePart {  
        return FunctionResponsePart(  
            name = call.name,  
            response = JsonObject(  
                mapOf(  
                    "status" to JsonPrimitive("error"),  
                    "message" to JsonPrimitive(errorMessage)  
                )  
            )  
        )  
    }  
}

## **6. Key Challenges & Decisions**

* **Authentication:** The Home Assistant Long-Lived Access Token must be securely stored in the Android app. For this prototype, we'll hardcode it in the repository, but a production app would need to use encrypted SharedPreferences or the Android Keystore system.  
* **Dynamic JSON Body:** The use of Map<String, Any> as the Retrofit @Body is critical. It allows us to pass any arguments (entity_id, brightness_pct, color_temp, etc.) without needing to define a separate data class for every possible HA service.  
* **Error Reporting:** Passing a clear error message back to Gemini (e.g., "HA Error 404: Entity 'light.fake_light' not found") is vital. This will allow the LLM to give an intelligent response to the user ("I'm sorry, I couldn't find a light with that name") instead of just failing silently.