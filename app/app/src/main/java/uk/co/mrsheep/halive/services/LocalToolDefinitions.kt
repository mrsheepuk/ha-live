package uk.co.mrsheep.halive.services

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema

object LocalToolDefinitions {

    /**
     * Creates the EndConversation tool that immediately terminates the conversation.
     *
     * Important: The model should say goodbye BEFORE calling this tool if it wants to bid
     * farewell, because once called, the conversation stops instantly.
     *
     * @return A FunctionDeclaration for the EndConversation tool
     */
    fun createEndConversationTool(): FunctionDeclaration {
        return FunctionDeclaration(
            name = "EndConversation",
            description = "IMMEDIATELY ends the conversation the moment this tool is called. " +
                    "The model should say goodbye BEFORE calling this tool if it wants to bid " +
                    "farewell, because once called, the conversation stops instantly. Use this " +
                    "when the conversation has naturally concluded, the user says goodbye, " +
                    "tasks are complete, etc. Do NOT use prematurely.",
            parameters = mapOf(
                "reason" to Schema.string("Brief explanation for ending (for logging)")
            ),
            optionalParameters = listOf("reason")
        )
    }
}
