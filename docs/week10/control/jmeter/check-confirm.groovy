import groovy.json.JsonSlurper

if (!(SampleResult.responseCode in ['200', '202'])) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('Expected HTTP 200 (completed) or HTTP 202 (accepted)')
    return
}

try {
    def body = new JsonSlurper().parse(SampleResult.responseData, 'UTF-8')
    if (body.meta?.result == 'SUCCESS' && body.data?.orderId instanceof Number
            && body.data.orderId.toString() == vars.get('orderId')) {
        return
    }
} catch (RuntimeException invalidJson) {
    // Record a failed sample without including the response body.
}
AssertionResult.setFailure(true)
AssertionResult.setFailureMessage('Expected a successful confirmation envelope for the requested order')
