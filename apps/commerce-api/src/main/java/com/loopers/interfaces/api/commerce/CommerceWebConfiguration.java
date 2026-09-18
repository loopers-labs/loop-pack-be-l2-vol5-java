package com.loopers.interfaces.api.commerce;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.List;

@Slf4j
@Configuration
public class CommerceWebConfiguration implements WebMvcConfigurer {
    private final CommerceErrors errors;

    public CommerceWebConfiguration(CommerceErrors errors) {
        this.errors = errors;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.registerObjectMappersForType(JsonNode.class, mappings -> mappings.put(
                    MediaType.APPLICATION_JSON,
                    jackson.getObjectMapper().copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                ));
            }
        }
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, (request, response, handler, exception) -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!isCommerce(path) || exception instanceof NoResourceFoundException) {
                return null;
            }
            CommerceErrors.Failure failure = errors.translate(exception);
            if (failure.status() == 500) {
                log.error("Unexpected commerce failure", exception);
            }
            try {
                errors.write(response, failure);
                return new ModelAndView();
            } catch (IOException writeFailure) {
                log.error("Cannot write commerce error response", writeFailure);
                return null;
            }
        });
    }

    private boolean isCommerce(String path) {
        return path.startsWith("/api-admin/v1/")
            || path.matches("/api/v1/(brands|products|users|points|orders)(/.*)?");
    }
}
