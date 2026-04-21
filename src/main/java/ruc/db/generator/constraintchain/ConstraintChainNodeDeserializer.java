package ruc.db.generator.constraintchain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintChainPkJoinNode;

import java.io.IOException;

import ruc.db.utils.CommonUtils;

public class ConstraintChainNodeDeserializer extends StdDeserializer<ConstraintChainNode> {

    public ConstraintChainNodeDeserializer() {
        this(null);
    }

    public ConstraintChainNodeDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public ConstraintChainNode deserialize(JsonParser parser, DeserializationContext deserializationContext) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        // 与 CommonUtils.MAPPER 一致：避免裸 ObjectMapper 与全局 MAPPER 在 BigDecimal / 省略策略上不一致导致 ReadAndWriteJsonTest 往返失败
        ObjectMapper mapper = CommonUtils.MAPPER.copy();
        return switch (ConstraintChainNodeType.valueOf(node.get("constraintChainNodeType").asText())) {
            case FILTER -> mapper.readValue(node.toString(), ConstraintChainFilterNode.class);
            case FK_JOIN -> mapper.readValue(node.toString(), ConstraintChainFkJoinNode.class);
            case PK_JOIN -> mapper.readValue(node.toString(), ConstraintChainPkJoinNode.class);
            case AGGREGATE -> mapper.readValue(node.toString(), ConstraintChainAggregateNode.class);
        };
    }
}
