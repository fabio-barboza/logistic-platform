package br.com.fabio.logisticagent.dto.render;

import java.util.List;

public record Dataset(String label, List<Number> data) {
}
