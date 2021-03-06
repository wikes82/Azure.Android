package com.azure.data.integration

import com.azure.data.model.Document
import com.azure.data.model.User
import java.util.*

/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

class CustomDocument(id: String? = null) : Document(id) {

    var customString = "My Custom String"
    var customNumber = 0
    var customDate: Date = Date()
    var customBool = false
    var customArray = arrayOf(1, 2, 3)
    var customObject: User? = null
}