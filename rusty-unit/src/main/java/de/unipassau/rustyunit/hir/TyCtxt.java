package de.unipassau.rustyunit.hir;

import static java.util.stream.Collectors.toCollection;

import de.unipassau.rustyunit.test_case.Param;
import de.unipassau.rustyunit.test_case.callable.std.option.OptionCallable.OptionNoneInit;
import de.unipassau.rustyunit.test_case.callable.std.option.OptionCallable.OptionSomeInit;
import de.unipassau.rustyunit.type.std.hash.Hasher;
import de.unipassau.rustyunit.test_case.var.VarReference;
import de.unipassau.rustyunit.test_case.callable.Callable;
import de.unipassau.rustyunit.test_case.callable.Method;
import de.unipassau.rustyunit.test_case.callable.rand.StepRngInit;
import de.unipassau.rustyunit.test_case.callable.std.StringInit;
import de.unipassau.rustyunit.type.Type;
import de.unipassau.rustyunit.type.rand.rngs.mock.StepRng;
import de.unipassau.rustyunit.type.std.option.Option;
import de.unipassau.rustyunit.type.std.vec.Vec;
import de.unipassau.rustyunit.type.traits.Trait;
import de.unipassau.rustyunit.util.TypeUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TyCtxt {

  private static final Logger logger = LoggerFactory.getLogger(TyCtxt.class);
  private static final Set<Type> types = new HashSet<>();

  static {
    types.add(StepRng.INSTANCE);
    types.add(Hasher.INSTANCE);
  }

  private final List<Callable> callables = loadBaseCallables();

  public TyCtxt(List<Callable> callables) throws IOException {
    callables = callables.stream()
        .filter(c -> !c.returnsValue() || (!(c.getReturnType() instanceof Vec)
            && !(c.getReturnType() instanceof Option)) || !c.getReturnType().generics().isEmpty())
        .toList();
    this.callables.addAll(callables);
    Collections.shuffle(this.callables);
    analysis();

    // Wow that's increadibly ugly
    TypeUtil.tyCtxt = this;
  }

  private static List<Callable> loadBaseCallables() {
    var baseCallables = new ArrayList<Callable>();

    // Option
    baseCallables.add(new OptionNoneInit());
    baseCallables.add(new OptionSomeInit());

    // TODO: 21.03.22 result

    // Mocked random generator
    baseCallables.add(new StepRngInit());

    // String
    baseCallables.add(new StringInit());

    return baseCallables;
  }

  private void analysis() {
    for (Callable callable : callables) {
      if (callable.getParent() != null) {
        var parent = callable.getParent();
        addType(parent);
      }

      for (Param param : callable.getParams()) {
        addType(param.type());
      }

      if (callable.getReturnType() != null) {
        addType(callable.getReturnType());
      }
    }
  }

  private void addType(Type type) {
    if (type.isGeneric() || type.isPrim()) {
      // Skip for now
    } else if (type.isRef()) {
      addType(type.asRef().getInnerType());
    } else if (type.isTuple()) {
      type.asTuple().getTypes().forEach(this::addType);
    } else if (type.isStruct()) {
      types.add(type);
    } else if (type.isEnum()) {
      types.add(type);
    } else if (type.isArray()) {
      addType(type.asArray().type());
    } else if (type.isSlice()) {
      addType(type.asSlice().type());
    } else if (type.isTraitObj()) {
      types.add(type);
    } else if (type.isFn()) {
      types.add(type);
    } else {
      throw new RuntimeException("Not implemented: " + type);
    }
  }

  public Set<Type> getTypes() {
    return types;
  }

  public List<Type> typesImplementing(List<Trait> bounds) {
    // Ignore Sized for now, all our types are sized by default
    var filteredBounds = bounds.stream()
        .filter(bound -> !bound.getName().equals("std::marker::Sized")).toList();

    return typesImplementingFiltered(filteredBounds);
  }

  private List<Type> typesImplementingFiltered(List<Trait> bounds) {
    var result = types.stream().filter(type -> type.implementedTraits().containsAll(bounds))
        .toList();
    return result;
  }

  public List<Callable> getCallablesOf(Type type) {
    throw new RuntimeException("Not implemented");
  }

  public List<Callable> getCallables() {
    return callables;
  }

  public List<Callable> getCallables(boolean localOnly) {
    return getCallables(null, true);
  }

  public List<Callable> getCallables(String filePath, boolean localOnly) {
    var stream = callables.stream();
    if (filePath != null) {
      stream = stream.filter(
          callable -> callable.isPublic()
              || (callable.getSrcFilePath() != null
                  && callable.getSrcFilePath().equals(filePath)));
    }

    if (localOnly) {
      stream = stream.filter(callable -> callable.getSrcFilePath() != null);
    }

    return stream.toList();
  }

  public List<Pair<VarReference, Method>> methodsOf(List<VarReference> variables) {

    var allmethods = variables.stream().map(v -> v.type().methods()).collect(Collectors.toSet());

    var methodsOfVariables = variables.stream().map(v -> v.type().methods()).flatMap(
        Collection::stream);
    var callables = this.callables.stream();
    return Stream.concat(methodsOfVariables, callables)
        .filter(Callable::isMethod)
        .map(callable -> (Method) callable)
        .map(method -> variables.stream()
            .filter(v -> method.getParent().canBeSameAs(v.type()))
            .filter(v -> {
              var selfParam = method.getSelfParam();
              var testCase = v.testCase();
              if (selfParam.isByReference()) {
                return v.isBorrowableAt(testCase.size());
              } else {
                return v.isConsumableAt(testCase.size());
              }
            })
            .map(v -> Pair.with(v, method))
            .toList())
        .flatMap(List::stream)
        .collect(Collectors.toList());
  }

  public List<Callable> callablesWithParam(Type type, String filePath, boolean onlyBorrowing,
      boolean onlyLocal) {
    var stream = callables.stream()
        .filter(c -> {
          var param = c.getParams().stream().filter(p -> p.type().canBeSameAs(type)).findFirst();
          if (param.isEmpty()) {
            return false;
          } else {
            if (onlyBorrowing) {
              return param.get().isByReference();
            }

            return true;
          }
        });

    if (onlyLocal) {
      stream = stream.filter(c -> c.getSrcFilePath() != null);
    }

    if (filePath != null) {
      stream = stream.filter(c -> c.isPublic() || c.getSrcFilePath().equals(filePath));
    }

    return stream.collect(Collectors.toList());
  }

  public List<Callable> generatorsOf(Type type, String filePath) {
    return generatorsOf(type, filePath, Callable.class);
  }

  /**
   * Returns the generators which can either generate a type that 1) is the same,
   * e.g., u32 == u32
   * 2) is generic and can be the given type wrt the trait bounds, e.g., T:
   * Default == u32 3) is a
   * container and some inner type can be same as given type, e.g., Vec<u32> ==
   * u32
   *
   * @param type The type to look for.
   * @return The generators of the type.
   */
  public <S extends Callable> List<Callable> generatorsOf(Type type, String filePath,
      Class<S> subClass) {
    logger.debug("Looking for generators of " + type.getName());

    var typeMethodsStream = type.methods().stream()
        .filter(m -> m.getReturnType() != null && !m.getReturnType().isGeneric());
    var callablesStream = callables.stream();

    // var stream = Stream.concat(typeMethodsStream, callablesStream)
    // .filter(subClass::isInstance)
    // .filter(callable -> callable.returnsValue()
    // && (callable.getReturnType().canBeSameAs(type)));
    // Unless we want the type explicitly, exclude completely generic callables like
    // Option::unwrap(Option) -> T, which would generate a wrapper just to unwrap it
    // later
    // .filter(callable ->
    // (callable.getReturnType().getName().equals(type.getName()))
    // || !callable.getReturnType().isGeneric());
    var stream = Stream.concat(typeMethodsStream, callablesStream)
        .filter(subClass::isInstance)
        .filter(callable -> callable.returnsValue()
            && (!callable.getReturnType().getName().isEmpty()
                && (callable.getReturnType().getName().equals(type.getName()))
                && !callable.getReturnType().isGeneric()));

    List<Callable> generators;
    if (filePath != null) {
      logger.debug("File path is not null, applying filtering");
      stream = stream.filter(callable -> callable.isPublic()
          || (callable.getSrcFilePath() != null && callable.getSrcFilePath().equals(filePath)));
      generators = stream.collect(toCollection(ArrayList::new));
      return generators;
    } else {
      // Only consider local callables first. If there are none, continue with all
      generators = stream.collect(toCollection(ArrayList::new));

      var localGenerators = generators.stream()
          .filter(callable -> callable.getSrcFilePath() != null)
          .collect(Collectors.toCollection(ArrayList::new));
      if (!localGenerators.isEmpty()) {
        generators = localGenerators;
      }
    }

    return generators;
  }

  public <S extends Callable> List<Callable> wrappingGeneratorsOf(Type type, String filePath) {
    return wrappingGeneratorsOf(type, filePath, Callable.class);
  }

  private <S extends Callable> List<Callable> wrappingGeneratorsOf(Type type, String filePath,
      Class<S> subClass) {
    logger.debug("Looking for wrapping generators of " + type);
    var stream = callables.stream()
        .filter(subClass::isInstance)
        .filter(callable -> callable.returnsValue()
            && callable.getReturnType().wraps(type).isPresent());
    if (filePath != null) {
      logger.debug("File path is not null, applying filtering");
      stream = stream.filter(callable -> callable.isPublic()
          || (callable.getSrcFilePath() != null && callable.getSrcFilePath().equals(filePath)));
    }

    var generators = stream.collect(toCollection(ArrayList::new));
    return generators;
  }

  public <S extends Callable> List<Callable> generatorsOf(Type owner, Type type, String filePath,
      Class<S> subClass) {
    logger.debug("Looking for generators of " + type + " by " + owner);
    var stream = callables.stream()
        .filter(subClass::isInstance)
        .filter(callable -> callable.getParent() != null && callable.getParent().equals(owner))
        .filter(callable -> callable.returnsValue() && callable.getReturnType()
            .canBeIndirectlySameAs(type));
    if (filePath != null) {
      logger.debug("File path is not null, applying filtering");
      stream = stream.filter(callable -> callable.isPublic()
          || (callable.getSrcFilePath() != null && callable.getSrcFilePath().equals(filePath)));
    }

    return stream.collect(toCollection(ArrayList::new));
  }
}
