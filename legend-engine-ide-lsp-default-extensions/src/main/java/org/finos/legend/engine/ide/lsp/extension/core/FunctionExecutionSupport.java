/*
 * Copyright 2024 Goldman Sachs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finos.legend.engine.ide.lsp.extension.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.map.mutable.MapAdapter;
import org.eclipse.collections.impl.tuple.Tuples;
import org.eclipse.collections.impl.utility.Iterate;
import org.eclipse.collections.impl.utility.LazyIterate;
import org.finos.legend.engine.entitlement.model.entitlementReport.DatasetEntitlementReport;
import org.finos.legend.engine.entitlement.model.specification.DatasetSpecification;
import org.finos.legend.engine.entitlement.services.EntitlementModelObjectMapperFactory;
import org.finos.legend.engine.entitlement.services.EntitlementServiceExtension;
import org.finos.legend.engine.entitlement.services.EntitlementServiceExtensionLoader;
import org.finos.legend.engine.ide.lsp.extension.AbstractLSPGrammarExtension;
import org.finos.legend.engine.ide.lsp.extension.CompileResult;
import org.finos.legend.engine.ide.lsp.extension.Constants;
import org.finos.legend.engine.ide.lsp.extension.SourceInformationUtil;
import org.finos.legend.engine.ide.lsp.extension.execution.LegendExecutionResult;
import org.finos.legend.engine.ide.lsp.extension.execution.LegendInputParameter;
import org.finos.legend.engine.ide.lsp.extension.repl.extension.LegendREPLExtensionFeature;
import org.finos.legend.engine.ide.lsp.extension.state.CancellationToken;
import org.finos.legend.engine.ide.lsp.extension.state.GlobalState;
import org.finos.legend.engine.ide.lsp.extension.state.SectionState;
import org.finos.legend.engine.ide.lsp.extension.text.TextLocation;
import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperRuntimeBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperValueSpecificationBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.language.pure.grammar.to.DEPRECATED_PureGrammarComposerCore;
import org.finos.legend.engine.plan.execution.PlanExecutionContext;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.nodes.helpers.ExecuteNodeParameterTransformationHelper;
import org.finos.legend.engine.plan.execution.result.ConstantResult;
import org.finos.legend.engine.plan.execution.result.ErrorResult;
import org.finos.legend.engine.plan.execution.result.Result;
import org.finos.legend.engine.plan.execution.result.StreamingResult;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.stores.StoreExecutableManager;
import org.finos.legend.engine.plan.generation.PlanGenerator;
import org.finos.legend.engine.plan.generation.PlanWithDebug;
import org.finos.legend.engine.plan.generation.extension.PlanGeneratorExtension;
import org.finos.legend.engine.plan.generation.transformers.PlanTransformer;
import org.finos.legend.engine.plan.platform.PlanPlatform;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity;
import org.finos.legend.engine.protocol.pure.m3.relation.RelationType;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.Variable;
import org.finos.legend.engine.protocol.pure.v1.PureProtocolObjectMapperFactory;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContext;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.ExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.Runtime;
import org.finos.legend.engine.protocol.pure.v1.model.type.PackageableType;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.Lambda;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.executionContext.ExecutionContext;
import org.finos.legend.engine.pure.code.core.PureCoreExtensionLoader;
import org.finos.legend.engine.repl.autocomplete.Completer;
import org.finos.legend.engine.repl.autocomplete.CompleterExtension;
import org.finos.legend.engine.repl.autocomplete.CompletionResult;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.api.grammar.GrammarAPI;
import org.finos.legend.engine.shared.core.api.grammar.RenderStyle;
import org.finos.legend.engine.shared.core.api.request.RequestContext;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.finos.legend.engine.shared.core.kerberos.SubjectTools;
import org.finos.legend.engine.shared.javaCompiler.JavaCompileException;
import org.finos.legend.pure.generated.Root_meta_core_runtime_Runtime;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.Mapping;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.FunctionDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public interface FunctionExecutionSupport
{
    Logger LOGGER = LoggerFactory.getLogger(FunctionExecutionSupport.class);

    String GET_EXECUTE_FUNCTION_DESCRIPTION_ID = "legend.function.execute.description";
    String EXECUTE_COMMAND_ID = "legend.function.execute";
    String EXECUTE_QUERY_ID = "legend.query.execute";
    String GET_QUERY_TYPEAHEAD = "legend.query.typeahead";
    String GENERATE_EXECUTION_PLAN_ID = "legend.executionPlan.generate";
    String GRAMMAR_TO_JSON_LAMBDA_BATCH_ID = "legend.grammarToJson.lambda.batch";
    String JSON_TO_GRAMMAR_LAMBDA_BATCH_ID = "legend.jsonToGrammar.lambda.batch";
    String GET_LAMBDA_RETURN_TYPE_ID = "legend.lambda.returnType";
    String SURVEY_DATASETS_ID = "legend.entitlements.surveyDatasets";
    String CHECK_DATASET_ENTITLEMENTS_ID = "legend.entitlements.checkDatasetEntitlements";

    ObjectMapper objectMapper = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();
    ObjectMapper entitlementModelObjectMapper = EntitlementModelObjectMapperFactory.getNewObjectMapper();

    AbstractLSPGrammarExtension getExtension();

    Lambda getLambda(PackageableElement element);

    @Deprecated
    default String getExecutionKey(PackageableElement element, Map<String, Object> args)
    {
        return "";
    }

    static Iterable<? extends LegendExecutionResult> execute(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, String commandId, Map<String, String> executableArgs, Map<String, Object> inputParameters, CancellationToken requestId)
    {
        switch (commandId)
        {
            case FunctionExecutionSupport.GET_EXECUTE_FUNCTION_DESCRIPTION_ID:
            {
                return FunctionExecutionSupport.getExecuteFunctionDescription(executionSupport, section, entityPath);
            }
            case FunctionExecutionSupport.EXECUTE_COMMAND_ID:
            {
                return FunctionExecutionSupport.executeFunction(executionSupport, section, entityPath, inputParameters, requestId);
            }
            case FunctionExecutionSupport.EXECUTE_QUERY_ID:
            {
                return FunctionExecutionSupport.executeQuery(executionSupport, section, entityPath, executableArgs, inputParameters, requestId);
            }
            case FunctionExecutionSupport.GET_QUERY_TYPEAHEAD:
            {
                return FunctionExecutionSupport.getQueryTypeahead(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            case FunctionExecutionSupport.GENERATE_EXECUTION_PLAN_ID:
            {
                return FunctionExecutionSupport.generateExecutionPlan(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            case FunctionExecutionSupport.GRAMMAR_TO_JSON_LAMBDA_BATCH_ID:
            {
                return FunctionExecutionSupport.convertGrammarToLambdaJsonBatch(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            case FunctionExecutionSupport.JSON_TO_GRAMMAR_LAMBDA_BATCH_ID:
            {
                return FunctionExecutionSupport.convertLambdaJsonToGrammarBatch(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            case FunctionExecutionSupport.GET_LAMBDA_RETURN_TYPE_ID:
            {
                return FunctionExecutionSupport.getLambdaReturnType(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            case FunctionExecutionSupport.SURVEY_DATASETS_ID:
            {
                return FunctionExecutionSupport.generateDatasetSpecifications(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            case FunctionExecutionSupport.CHECK_DATASET_ENTITLEMENTS_ID:
            {
                return FunctionExecutionSupport.generateEntitlementReports(executionSupport, section, entityPath, executableArgs, inputParameters);
            }
            default:
            {
                throw new UnsupportedOperationException("Unsupported command: " + commandId);
            }
        }
    }

    SingleExecutionPlan getExecutionPlan(PackageableElement element, Lambda lambda, PureModel pureModel, Map<String, Object> args, String clientVersion);

    static SingleExecutionPlan getExecutionPlan(Lambda lambda, String mappingPath, Runtime runtime, ExecutionContext context, PureModel pureModel, String clientVersion)
    {
        FunctionDefinition<?> functionDefinition = HelperValueSpecificationBuilder.buildLambda(lambda.body, lambda.parameters, pureModel.getContext());
        Mapping mapping = mappingPath == null ? null : pureModel.getMapping(mappingPath);
        Root_meta_core_runtime_Runtime pureRuntime = HelperRuntimeBuilder.buildPureRuntime(runtime, pureModel.getContext());

        MutableList<? extends Root_meta_pure_extension_Extension> routerExtensions = PureCoreExtensionLoader.extensions().flatCollect(e -> e.extraPureCoreExtensions(pureModel.getExecutionSupport()));
        MutableList<PlanTransformer> planTransformers = Iterate.flatCollect(ServiceLoader.load(PlanGeneratorExtension.class), PlanGeneratorExtension::getExtraPlanTransformers, Lists.mutable.empty());
        return PlanGenerator.generateExecutionPlan(
                functionDefinition,
                mapping,
                pureRuntime,
                HelperValueSpecificationBuilder.processExecutionContext(context, pureModel.getContext()),
                pureModel,
                clientVersion,
                PlanPlatform.JAVA,
                null,
                routerExtensions,
                planTransformers
        );
    }

    static PlanWithDebug debugExecutionPlan(Lambda lambda, String mappingPath, Runtime runtime, ExecutionContext context, PureModel pureModel, String clientVersion)
    {
        FunctionDefinition<?> functionDefinition = HelperValueSpecificationBuilder.buildLambda(lambda.body, lambda.parameters, pureModel.getContext());
        Mapping mapping = pureModel.getMapping(mappingPath);
        Root_meta_core_runtime_Runtime pureRuntime = HelperRuntimeBuilder.buildPureRuntime(runtime, pureModel.getContext());

        MutableList<? extends Root_meta_pure_extension_Extension> routerExtensions = PureCoreExtensionLoader.extensions().flatCollect(e -> e.extraPureCoreExtensions(pureModel.getExecutionSupport()));
        MutableList<PlanTransformer> planTransformers = Iterate.flatCollect(ServiceLoader.load(PlanGeneratorExtension.class), PlanGeneratorExtension::getExtraPlanTransformers, Lists.mutable.empty());
        return PlanGenerator.generateExecutionPlanDebug(
                functionDefinition,
                mapping,
                pureRuntime,
                HelperValueSpecificationBuilder.processExecutionContext(context, pureModel.getContext()),
                pureModel,
                clientVersion,
                PlanPlatform.JAVA,
                null,
                routerExtensions,
                planTransformers
        );
    }

    List<Variable> getParameters(PackageableElement element);

    static Iterable<? extends LegendExecutionResult> getExecuteFunctionDescription(FunctionExecutionSupport executionSupport, SectionState section, String entityPath)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        if (compileResult.hasEngineException())
        {
            return Collections.singletonList(extension.errorResult(compileResult.getCompileErrorResult(), entityPath));
        }

        List<PackageableElement> elements = compileResult.getPureModelContextData().getElements();
        PackageableElement element = elements.stream().filter(x -> x.getPath().equals(entityPath)).findFirst().orElseThrow(() -> new IllegalArgumentException("Element " + entityPath + " not found"));
        Map<String, Object> parameters = Maps.mutable.empty();
        List<Variable> funcParameters = executionSupport.getParameters(element);
        if (funcParameters != null && !funcParameters.isEmpty())
        {
            funcParameters.forEach(p ->
            {
                PackageableElement paramElement = elements.stream().filter(e -> e.getPath().equals(((PackageableType) p.genericType.rawType).fullPath)).findFirst().orElse(null);
                if (paramElement instanceof Enumeration)
                {
                    parameters.put(p.name, LegendFunctionInputParameter.newFunctionParameter(p, paramElement));
                }
                else
                {
                    parameters.put(p.name, LegendFunctionInputParameter.newFunctionParameter(p));
                }
            });
        }
        String uri = section.getDocumentState().getDocumentId();
        int sectionNumber = section.getSectionNumber();
        TextLocation textLocation = SourceInformationUtil.toLocation(element.sourceInformation);
        List<Object> executeFunctionDescription = List.of(uri, sectionNumber, entityPath, EXECUTE_COMMAND_ID, Collections.emptyMap(), parameters);

        try
        {
            String result = objectMapper.writeValueAsString(executeFunctionDescription);
            return Collections.singletonList(LegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS, result, textLocation));
        }
        catch (Exception e)
        {
            return Collections.singletonList(extension.errorResult(e, null, entityPath, textLocation));
        }
    }

    static Iterable<? extends LegendExecutionResult> executeFunction(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, Object> inputParameters, CancellationToken requestId)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        if (compileResult.hasEngineException())
        {
            return Collections.singletonList(extension.errorResult(compileResult.getCompileErrorResult(), entityPath));
        }

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            PackageableElement element = compileResult.getPureModelContextData().getElements().stream().filter(x -> x.getPath().equals(entityPath)).findFirst().orElseThrow(() -> new IllegalArgumentException("Element " + entityPath + " not found"));
            GlobalState globalState = section.getDocumentState().getGlobalState();
            String executionKey = executionSupport.getExecutionKey(element, inputParameters);

            Pair<SingleExecutionPlan, PlanExecutionContext> executionPlanAndContext = globalState.getProperty(EXECUTE_COMMAND_ID + ":" + entityPath + ":" + executionKey, () ->
            {
                Lambda lambda = executionSupport.getLambda(element);
                PureModel pureModel = compileResult.getPureModel();
                SingleExecutionPlan executionPlan = executionSupport.getExecutionPlan(element, lambda, pureModel, inputParameters, globalState.getSetting(Constants.LEGEND_PROTOCOL_VERSION));
                PlanExecutionContext planExecutionContext = null;
                try
                {
                    planExecutionContext = new PlanExecutionContext(executionPlan, List.of());
                }
                catch (JavaCompileException e)
                {
                    LOGGER.warn("Failed to compile plan");
                }

                return Tuples.pair(executionPlan, planExecutionContext);
            });
            executePlan(globalState, executionSupport, section.getDocumentState().getDocumentId(), section.getSectionNumber(), executionPlanAndContext.getOne(), executionPlanAndContext.getTwo(), entityPath, inputParameters, results, requestId);
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> executeQuery(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters, CancellationToken requestId)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        if (compileResult.hasEngineException())
        {
            return Collections.singletonList(extension.errorResult(compileResult.getCompileErrorResult(), entityPath));
        }

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            GlobalState globalState = section.getDocumentState().getGlobalState();
            Lambda lambda = objectMapper.readValue(executableArgs.get("lambda"), Lambda.class);
            String mappingPath = executableArgs.get("mapping");
            Runtime runtime = executableArgs.get("runtime") != null ? objectMapper.readValue(executableArgs.get("runtime"), Runtime.class) : null;
            ExecutionContext context = objectMapper.readValue(executableArgs.get("context"), ExecutionContext.class);
            SerializationFormat format = SerializationFormat.valueOf(executableArgs.getOrDefault("serializationFormat", SerializationFormat.DEFAULT.name()));
            PureModel pureModel = compileResult.getPureModel();
            String clientVersion = globalState.getSetting(Constants.LEGEND_PROTOCOL_VERSION);
            SingleExecutionPlan executionPlan = FunctionExecutionSupport.getExecutionPlan(lambda, mappingPath, runtime, context, pureModel, clientVersion);
            String exportFilePath = executableArgs.get("exportFilePath");

            PlanExecutionContext planExecutionContext = null;
            try
            {
                planExecutionContext = new PlanExecutionContext(executionPlan, List.of());
            }
            catch (JavaCompileException e)
            {
                LOGGER.warn("Failed to compile plan");
            }
            executePlan(globalState, executionSupport, section.getDocumentState().getDocumentId(), section.getSectionNumber(), executionPlan, planExecutionContext, entityPath, inputParameters, format, exportFilePath, results, requestId);
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static void executePlan(GlobalState globalState, FunctionExecutionSupport executionSupport, String docId, int sectionNum, SingleExecutionPlan executionPlan, PlanExecutionContext context, String entityPath, Map<String, Object> inputParameters, MutableList<LegendExecutionResult> results, CancellationToken requestId)
    {
        executePlan(globalState, executionSupport, docId, sectionNum, executionPlan, context, entityPath, inputParameters, SerializationFormat.DEFAULT, null, results, requestId);
    }

    static void executePlan(GlobalState globalState, FunctionExecutionSupport executionSupport, String docId, int sectionNum, SingleExecutionPlan executionPlan, PlanExecutionContext context, String entityPath, Map<String, Object> inputParameters, SerializationFormat format, String exportFilePath, MutableList<LegendExecutionResult> results, CancellationToken requestId)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        try
        {
            if (extension.isEngineServerConfigured()
                    && Boolean.parseBoolean(globalState.getSetting(Constants.LEGEND_ENGINE_SERVER_REMOTE_EXECUTION)))
            {
                ExecutionRequest executionRequest = new ExecutionRequest(executionPlan, inputParameters);
                LegendExecutionResult legendExecutionResult = extension.postEngineServer("/executionPlan/v1/execution/executeRequest?serializationFormat=DEFAULT", executionRequest, is ->
                {
                    ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
                    is.transferTo(os);
                    return FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS, os.toString(StandardCharsets.UTF_8), "Executed using remote engine server", docId, sectionNum, inputParameters);
                }, x -> requestId.listener(x::run));
                results.add(legendExecutionResult);
            }
            else
            {
                try (Result result = executePlan(executionPlan, context, inputParameters, extension, requestId))
                {
                    requestId.listener(result::close);
                    collectResults(executionSupport, entityPath, result, docId, sectionNum, inputParameters, format, results::add, exportFilePath);
                }
            }
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
    }

    static Result executePlan(SingleExecutionPlan executionPlan, PlanExecutionContext context, Map<String, Object> inputParameters, AbstractLSPGrammarExtension extension, CancellationToken requestId)
    {
        PlanExecutor planExecutor = extension.getPlanExecutor();
        MutableMap<String, Result> parametersToConstantResult = Maps.mutable.empty();
        ExecuteNodeParameterTransformationHelper.buildParameterToConstantResult(executionPlan, inputParameters, parametersToConstantResult);
        Identity identity = getIdentity();

        StoreExecutableManager.INSTANCE.registerManager();
        RequestContext requestContext = new RequestContext(requestId.getId(), "local-lsp", requestId.getId());
        requestId.listener(() -> StoreExecutableManager.INSTANCE.cancelExecutablesOnSession(requestId.getId()));

        return planExecutor.execute(executionPlan, parametersToConstantResult, identity.getName(), identity, context, requestContext);
    }

    static Iterable<? extends LegendExecutionResult> generateExecutionPlan(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        if (compileResult.hasEngineException())
        {
            return Collections.singletonList(extension.errorResult(compileResult.getCompileErrorResult(), entityPath));
        }

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            GlobalState globalState = section.getDocumentState().getGlobalState();

            Lambda lambda = objectMapper.readValue(executableArgs.get("lambda"), Lambda.class);
            String mappingPath = executableArgs.get("mapping");
            Runtime runtime = objectMapper.readValue(executableArgs.get("runtime"), Runtime.class);
            ExecutionContext context = objectMapper.readValue(executableArgs.get("context"), ExecutionContext.class);
            PureModel pureModel = compileResult.getPureModel();
            String clientVersion = globalState.getSetting(Constants.LEGEND_PROTOCOL_VERSION);

            boolean debug = Boolean.parseBoolean(executableArgs.get("debug"));

            String result = objectMapper.writeValueAsString(debug ?
                    FunctionExecutionSupport.debugExecutionPlan(lambda, mappingPath, runtime, context, pureModel, clientVersion) :
                    FunctionExecutionSupport.getExecutionPlan(lambda, mappingPath, runtime, context, pureModel, clientVersion));
            results.add(
                    FunctionLegendExecutionResult.newResult(
                            entityPath,
                            LegendExecutionResult.Type.SUCCESS,
                            result,
                            null,
                            section.getDocumentState().getDocumentId(),
                            section.getSectionNumber(),
                            inputParameters
                    )
            );
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> getQueryTypeahead(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        if (compileResult.hasEngineException())
        {
            return Collections.singletonList(extension.errorResult(compileResult.getCompileErrorResult(), entityPath));
        }

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();

        try
        {
            PureModel pureModel = compileResult.getPureModel();
            MutableList<CompleterExtension> completerExtensions = section.getDocumentState().getGlobalState()
                    .findFeatureThatImplements(LegendREPLExtensionFeature.class)
                    .map(LegendREPLExtensionFeature::getCompleterExtensions)
                    .flatMap(List::stream)
                    .collect(Collectors.toCollection(Lists.mutable::empty));

            String code = executableArgs.get("code");
            Lambda baseQuery = objectMapper.readValue(executableArgs.get("baseQuery"), Lambda.class);
            String baseQueryCode = baseQuery != null ? baseQuery.body.get(0).accept(DEPRECATED_PureGrammarComposerCore.Builder.newInstance().withRenderStyle(RenderStyle.STANDARD).build()) : null;
            String queryCode = (baseQueryCode != null ? baseQueryCode : "") + code;
            Completer completer = new Completer(pureModel, completerExtensions);
            CompletionResult result = completer.complete(queryCode);
            results.add(FunctionExecutionSupport.FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS,
                    objectMapper.writeValueAsString(result), null, section.getDocumentState().getDocumentId(), section.getSectionNumber(), inputParameters));
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> convertGrammarToLambdaJsonBatch(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            Map<String, GrammarAPI.ParserInput> input = objectMapper.readValue(executableArgs.get("input"), new TypeReference<>() {});
            Map<String, Lambda> result = org.eclipse.collections.api.factory.Maps.mutable.empty();

            MapAdapter.adapt(input).forEachKeyValue((key, value) -> result.put(key,
                    PureGrammarParser.newInstance().parseLambda(
                            value.value,
                            value.sourceInformationOffset == null ? "" : value.sourceInformationOffset.sourceId,
                            value.sourceInformationOffset == null ? 0 : value.sourceInformationOffset.lineOffset,
                            value.sourceInformationOffset == null ? 0 : value.sourceInformationOffset.columnOffset,
                            value.returnSourceInformation
                    )
            ));

            results.add(FunctionLegendExecutionResult.newResult(entityPath,
                    LegendExecutionResult.Type.SUCCESS,
                    objectMapper.writerFor(new TypeReference<Map<String,Lambda>>() {}).writeValueAsString(result),
                    null,
                    section.getDocumentState().getDocumentId(),
                    section.getSectionNumber(),
                    inputParameters
            ));
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> convertLambdaJsonToGrammarBatch(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            Map<String, Lambda> lambdas = objectMapper.readValue(executableArgs.get("lambdas"), new TypeReference<>() {});
            RenderStyle renderStyle = RenderStyle.valueOf(executableArgs.get("renderStyle"));
            Map<String, Object> result = org.eclipse.collections.api.factory.Maps.mutable.empty();
            MapAdapter.adapt(lambdas).forEachKeyValue((key, value) ->
                    result.put(key, value.accept(DEPRECATED_PureGrammarComposerCore.Builder.newInstance().withRenderStyle(renderStyle).build())));
            results.add(
                    FunctionLegendExecutionResult.newResult(
                            entityPath,
                            LegendExecutionResult.Type.SUCCESS,
                            objectMapper.writeValueAsString(result),
                            null,
                            section.getDocumentState().getDocumentId(),
                            section.getSectionNumber(),
                            inputParameters
                    )
            );
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> getLambdaReturnType(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        if (compileResult.hasEngineException())
        {
            return Collections.singletonList(extension.errorResult(compileResult.getCompileErrorResult(), entityPath));
        }

        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            PureModel pureModel = compileResult.getPureModel();
            Lambda lambda = objectMapper.readValue(executableArgs.get("lambda"), Lambda.class);
            String typeName = Compiler.getLambdaReturnType(lambda, pureModel);
            Map<String, Object> result = new HashMap<>();
            result.put("returnType", typeName);
            if (Objects.equals(typeName, "meta::pure::metamodel::relation::Relation") || Objects.equals(typeName, "meta::pure::store::RelationStoreAccessor"))
            {
                RelationType relationType = Compiler.getLambdaRelationType(lambda, pureModel);
                result.put("relationType", relationType);
            }
            results.add(
                    FunctionLegendExecutionResult.newResult(
                            entityPath,
                            LegendExecutionResult.Type.SUCCESS,
                            objectMapper.writeValueAsString(result),
                            null,
                            section.getDocumentState().getDocumentId(),
                            section.getSectionNumber(),
                            inputParameters
                    )
            );
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> generateDatasetSpecifications(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            List<EntitlementServiceExtension> entitlementServiceExtensions =
                    EntitlementServiceExtensionLoader.extensions();
            PureModel pureModel = compileResult.getPureModel();
            PureModelContext pureModelContext = compileResult.getPureModelContextData();
            String mappingPath = executableArgs.get("mapping");
            Mapping mapping = pureModel.getMapping(mappingPath);
            String runtimePath = executableArgs.get("runtime");
            Root_meta_core_runtime_Runtime runtime = pureModel.getRuntime(runtimePath);
            Lambda query = entitlementModelObjectMapper.readValue(executableArgs.get("lambda"), Lambda.class);
            List<DatasetSpecification> datasets = LazyIterate.flatCollect(entitlementServiceExtensions,
                    entitlementExtension -> entitlementExtension.generateDatasetSpecifications(query, runtimePath,
                            runtime, mappingPath, mapping, pureModelContext, pureModel)).toList();
            CollectionType datasetSpecificationListType =
                    entitlementModelObjectMapper.getTypeFactory().constructCollectionType(List.class,
                            DatasetSpecification.class);
            results.add(FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS,
                    entitlementModelObjectMapper.writer().forType(datasetSpecificationListType).writeValueAsString(datasets), null, section.getDocumentState().getDocumentId(), section.getSectionNumber(), inputParameters));
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    static Iterable<? extends LegendExecutionResult> generateEntitlementReports(FunctionExecutionSupport executionSupport, SectionState section, String entityPath, Map<String, String> executableArgs, Map<String, Object> inputParameters)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        CompileResult compileResult = extension.getCompileResult(section);
        MutableList<LegendExecutionResult> results = Lists.mutable.empty();
        try
        {
            List<EntitlementServiceExtension> entitlementServiceExtensions =
                    EntitlementServiceExtensionLoader.extensions();
            PureModel pureModel = compileResult.getPureModel();
            PureModelContext pureModelContext = compileResult.getPureModelContextData();
            String mappingPath = executableArgs.get("mapping");
            Mapping mapping = pureModel.getMapping(mappingPath);
            String runtimePath = executableArgs.get("runtime");
            Root_meta_core_runtime_Runtime runtime = pureModel.getRuntime(runtimePath);
            Lambda query = entitlementModelObjectMapper.readValue(executableArgs.get("lambda"), Lambda.class);
            List<DatasetSpecification> reports = entitlementModelObjectMapper.readValue(executableArgs.get("reports"), new TypeReference<>() {});
            List<DatasetEntitlementReport> result = LazyIterate.flatCollect(entitlementServiceExtensions,
                    entitlementExtension -> entitlementExtension.generateDatasetEntitlementReports(reports, query,
                            runtimePath, runtime, mappingPath, mapping, pureModelContext, pureModel, null)).toList();
            CollectionType datasetEntitlementReportListType =
                    entitlementModelObjectMapper.getTypeFactory().constructCollectionType(List.class,
                            DatasetEntitlementReport.class);
            results.add(FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS,
                    entitlementModelObjectMapper.writer().forType(datasetEntitlementReportListType).writeValueAsString(result), null, section.getDocumentState().getDocumentId(), section.getSectionNumber(), inputParameters));
        }
        catch (Exception e)
        {
            results.add(extension.errorResult(e, entityPath));
        }
        return results;
    }

    private static Identity getIdentity()
    {
        try
        {
            return Identity.makeIdentity(SubjectTools.getLocalSubject());
        }
        catch (Exception e)
        {
            return Identity.getAnonymousIdentity();
        }
    }

    static void collectResults(FunctionExecutionSupport executionSupport, String entityPath, org.finos.legend.engine.plan.execution.result.Result result, String docId, int secNum, Map<String, Object> inputParameters, SerializationFormat format, Consumer<? super LegendExecutionResult> consumer)
    {
        collectResults(executionSupport, entityPath, result, docId, secNum, inputParameters, format, consumer, null);
    }

    static void collectResults(FunctionExecutionSupport executionSupport, String entityPath, org.finos.legend.engine.plan.execution.result.Result result, String docId, int secNum, Map<String, Object> inputParameters, SerializationFormat format, Consumer<? super LegendExecutionResult> consumer, String exportFilePath)
    {
        AbstractLSPGrammarExtension extension = executionSupport.getExtension();

        // TODO also collect results from activities
        if (result instanceof ErrorResult)
        {
            ErrorResult errorResult = (ErrorResult) result;
            consumer.accept(FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.ERROR, errorResult.getMessage(), errorResult.getTrace(), docId, secNum, inputParameters));
            return;
        }
        if (result instanceof ConstantResult)
        {
            consumer.accept(FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS, getConstantResult((ConstantResult) result), null, docId, secNum, inputParameters));
            return;
        }
        if (result instanceof StreamingResult)
        {
            try (OutputStream outputStream = exportFilePath != null ? new FileOutputStream(exportFilePath) : new ByteArrayOutputStream(1024))
            {
                ((StreamingResult) result).getSerializer(format).stream(outputStream);
                String message = outputStream instanceof ByteArrayOutputStream
                                 ? ((ByteArrayOutputStream) outputStream).toString(StandardCharsets.UTF_8)
                                 : exportFilePath;
                consumer.accept(FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.SUCCESS, message, null, docId, secNum, inputParameters));
            }
            catch (IOException e)
            {
                consumer.accept(extension.errorResult(e, entityPath));
                return;
            }
            return;
        }
        consumer.accept(FunctionLegendExecutionResult.newResult(entityPath, LegendExecutionResult.Type.WARNING, "Unhandled result type: " + result.getClass().getName(), null, docId, secNum, inputParameters));
    }

    private static String getConstantResult(ConstantResult constantResult)
    {
        return getConstantValueResult(constantResult.getValue());
    }

    JsonMapper functionResultMapper = PureProtocolObjectMapperFactory.withPureProtocolExtensions(JsonMapper.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build());

    private static String getConstantValueResult(Object value)
    {
        if (value == null)
        {
            return "[]";
        }
        try
        {
            return functionResultMapper.writeValueAsString(value);
        }
        catch (Exception e)
        {
            LOGGER.error("Error converting value to JSON", e);
        }
        return value.toString();
    }

    class FunctionLegendExecutionResult extends LegendExecutionResult
    {
        private final String uri;
        private final int sectionNum;
        private final Map<String, Object> inputParameters;

        private FunctionLegendExecutionResult(List<String> ids, Type type, String message, String logMessage, String uri, int sectionNum, Map<String, Object> inputParameters, String messageType)
        {
            super(ids, type, message, logMessage, null, messageType);
            this.uri = uri;
            this.sectionNum = sectionNum;
            this.inputParameters = inputParameters;
        }

        public String getUri()
        {
            return uri;
        }

        public int getSectionNum()
        {
            return sectionNum;
        }

        public Map<String, Object> getInputParameters()
        {
            return inputParameters;
        }

        public static FunctionLegendExecutionResult newResult(String id, Type type, String message, String logMessage, String uri, int sectionNum, Map<String, Object> inputParameters, String messageType)
        {
            return new FunctionLegendExecutionResult(Collections.singletonList(id), type, message, logMessage, uri, sectionNum, inputParameters, messageType);
        }

        public static FunctionLegendExecutionResult newResult(String id, Type type, String message, String logMessage, String uri, int sectionNum, Map<String, Object> inputParameters)
        {
            return newResult(id, type, message, logMessage, uri, sectionNum, inputParameters, "json");
        }
    }

    class LegendFunctionInputParameter extends LegendInputParameter
    {
        private final LegendVariable variable;
        private final PackageableElement element;

        private LegendFunctionInputParameter(Variable variable, PackageableElement element)
        {
            this.variable = LegendVariable.create(variable);
            this.element = element;
        }

        public LegendVariable getVariable()
        {
            return this.variable;
        }

        public PackageableElement getElement()
        {
            return this.element;
        }

        public static LegendFunctionInputParameter newFunctionParameter(Variable variable)
        {
            return newFunctionParameter(variable, null);
        }

        public static LegendFunctionInputParameter newFunctionParameter(Variable variable, PackageableElement element)
        {
            return new LegendFunctionInputParameter(variable, element);
        }
    }

    class LegendVariable
    {
        private final String name;
        private final Multiplicity multiplicity;
        private final String _class;

        private LegendVariable(String name, Multiplicity multiplicity, String _class)
        {
            this.name = name;
            this.multiplicity = multiplicity;
            this._class = _class;
        }

        public static LegendVariable create(Variable variable)
        {
            return new LegendVariable(variable.name, variable.multiplicity, ((PackageableType) variable.genericType.rawType).fullPath);
        }

        public String getName()
        {
            return name;
        }

        public Multiplicity getMultiplicity()
        {
            return multiplicity;
        }

        public String get_class()
        {
            return _class;
        }
    }

    class ExecutionRequest
    {
        private final ExecutionPlan executionPlan;
        private final Map<String, Object> executionParameters;

        public ExecutionRequest(ExecutionPlan executionPlan, Map<String, Object> executionParameters)
        {
            this.executionPlan = executionPlan;
            this.executionParameters = executionParameters == null ? Collections.emptyMap() : executionParameters;
        }

        public ExecutionPlan getExecutionPlan()
        {
            return this.executionPlan;
        }

        public Map<String, Object> getExecutionParameters()
        {
            return this.executionParameters;
        }
    }

    static SingleExecutionPlan generateSingleExecutionPlan(PureModel pureModel, String clientVersion, FunctionDefinition<?> functionDefinition)
    {
        MutableList<? extends Root_meta_pure_extension_Extension> routerExtensions = PureCoreExtensionLoader.extensions().flatCollect(e -> e.extraPureCoreExtensions(pureModel.getExecutionSupport()));
        MutableList<PlanTransformer> planTransformers = Iterate.flatCollect(ServiceLoader.load(PlanGeneratorExtension.class), PlanGeneratorExtension::getExtraPlanTransformers, Lists.mutable.empty());
        return PlanGenerator.generateExecutionPlan(functionDefinition, null, null, null, pureModel, clientVersion, PlanPlatform.JAVA, null, routerExtensions, planTransformers);
    }
}
