package com.loopers.interfaces.api;

import com.loopers.domain.product.ProductAdminQuery;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.product.ProductViewQuery;
import com.loopers.domain.ranking.RankingLimit;
import com.loopers.domain.ranking.WeekStart;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebQueryParamConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToProductSort());
        registry.addConverter(new StringToPageSize());
        registry.addConverter(new StringToPageNumber());
        registry.addConverter(new StringToProductAdminSort());
        registry.addConverter(new StringToWeekStart());
        registry.addConverter(new StringToRankingLimit());
    }

    static class StringToProductSort implements Converter<String, ProductViewQuery.Sort> {
        @Override
        public ProductViewQuery.Sort convert(String source) {
            return ProductViewQuery.Sort.valueOf(source.trim().toUpperCase());
        }
    }

    static class StringToPageNumber implements Converter<String, PageNumber> {
        @Override
        public PageNumber convert(String source) {
            return PageNumber.of(Integer.parseInt(source.trim()));
        }
    }

    static class StringToProductAdminSort implements Converter<String, ProductAdminQuery.Sort> {
        @Override
        public ProductAdminQuery.Sort convert(String source) {
            return ProductAdminQuery.Sort.valueOf(source.trim().toUpperCase());
        }
    }

    static class StringToPageSize implements Converter<String, PageSize> {
        @Override
        public PageSize convert(String source) {
            return PageSize.of(Integer.parseInt(source.trim()));
        }
    }

    static class StringToWeekStart implements Converter<String, WeekStart> {
        @Override
        public WeekStart convert(String source) {
            return WeekStart.of(source.trim());
        }
    }

    static class StringToRankingLimit implements Converter<String, RankingLimit> {
        @Override
        public RankingLimit convert(String source) {
            return RankingLimit.of(Integer.parseInt(source.trim()));
        }
    }
}
