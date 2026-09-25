package com.example.eva.tools

object CalculatorTool {

    fun calculate(query: String): ToolExecutionResult {
        val q = query.lowercase().trim()

        // Check for percentage: "15 percent of 800", "15% of 800"
        val percentRegex = Regex("""(\d+(\.\d+)?)\s*(percent|%)\s*of\s*(\d+(\.\d+)?)""")
        percentRegex.find(q)?.let { match ->
            val p = match.groupValues[1].toDoubleOrNull()
            val total = match.groupValues[4].toDoubleOrNull()
            if (p != null && total != null) {
                val result = (p / 100.0) * total
                val formatted = if (result % 1.0 == 0.0) result.toInt().toString() else "%.2f".format(result)
                return ToolExecutionResult(true, "$p% of $total is $formatted.")
            }
        }

        // Check for unit conversions: "convert X kilometers to meters", etc.
        val kmToM = Regex("""convert\s*(\d+(\.\d+)?)\s*(km|kilometers|kilometer)\s*to\s*(m|meters|meter)""")
        kmToM.find(q)?.let { match ->
            val v = match.groupValues[1].toDoubleOrNull()
            if (v != null) {
                val meters = v * 1000
                return ToolExecutionResult(true, "$v kilometers is $meters meters.")
            }
        }

        val mToKm = Regex("""convert\s*(\d+(\.\d+)?)\s*(m|meters|meter)\s*to\s*(km|kilometers|kilometer)""")
        mToKm.find(q)?.let { match ->
            val v = match.groupValues[1].toDoubleOrNull()
            if (v != null) {
                val km = v / 1000.0
                return ToolExecutionResult(true, "$v meters is $km kilometers.")
            }
        }

        val kgToG = Regex("""convert\s*(\d+(\.\d+)?)\s*(kg|kilograms|kilogram)\s*to\s*(g|grams|gram)""")
        kgToG.find(q)?.let { match ->
            val v = match.groupValues[1].toDoubleOrNull()
            if (v != null) {
                val g = v * 1000
                return ToolExecutionResult(true, "$v kilograms is $g grams.")
            }
        }

        // Check for basic operations: "25 times 40", "100 divided by 4", "50 plus 20", "80 minus 30"
        val cleanExpr = q
            .replace("times", "*")
            .replace("multiplied by", "*")
            .replace("x", "*")
            .replace("into", "*")
            .replace("divided by", "/")
            .replace("over", "/")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("what is", "")
            .replace("calculate", "")
            .replace("?", "")
            .trim()

        // Match binary arithmetic: A [operator] B
        val binaryRegex = Regex("""(-?\d+(\.\d+)?)\s*([\+\-\*\/])\s*(-?\d+(\.\d+)?)""")
        binaryRegex.find(cleanExpr)?.let { match ->
            val a = match.groupValues[1].toDoubleOrNull()
            val op = match.groupValues[3]
            val b = match.groupValues[4].toDoubleOrNull()

            if (a != null && b != null) {
                val res = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b == 0.0) Double.NaN else a / b
                    else -> Double.NaN
                }
                if (res.isNaN()) {
                    return ToolExecutionResult(false, "Cannot divide by zero.")
                }
                val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res)
                return ToolExecutionResult(true, "The result of $a $op $b is $formatted.")
            }
        }

        return ToolExecutionResult(false, "Could not compute mathematical expression.")
    }
}
