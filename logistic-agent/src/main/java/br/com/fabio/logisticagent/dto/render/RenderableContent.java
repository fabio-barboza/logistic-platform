package br.com.fabio.logisticagent.dto.render;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChartContent.class, name = "chart"),
        @JsonSubTypes.Type(value = TableContent.class, name = "table")
})
public sealed interface RenderableContent permits ChartContent, TableContent {
}
