import groovy.json.JsonSlurper

if (prev.responseCode == '201') {
    def body = new JsonSlurper().parseText(prev.responseDataAsString)
    if (body.meta?.result == 'SUCCESS' && body.data?.orderId instanceof Number) {
        vars.put('orderId', body.data.orderId.toString())
        return
    }
}
prev.setSuccessful(false)
