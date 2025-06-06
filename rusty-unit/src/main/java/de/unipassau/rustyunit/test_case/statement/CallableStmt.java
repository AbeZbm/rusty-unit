package de.unipassau.rustyunit.test_case.statement;

import com.google.common.collect.Streams;
import de.unipassau.rustyunit.test_case.Param;
import de.unipassau.rustyunit.test_case.TestCase;
import de.unipassau.rustyunit.test_case.var.VarReference;
import de.unipassau.rustyunit.type.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CallableStmt implements Statement {
  private static Logger logger = LoggerFactory.getLogger(CallableStmt.class);

  protected UUID id;
  private TestCase testCase;
  protected List<VarReference> args;
  protected VarReference returnValue;

  public CallableStmt(TestCase testCase, List<VarReference> args,
      VarReference returnValue) {
    this.id = UUID.randomUUID();
    this.testCase = testCase;
    this.args = args;
    this.returnValue = returnValue;
  }
  @Override
  public TestCase testCase() {
    return testCase;
  }

  @Override
  public List<VarReference> args() {
    return args;
  }

  @Override
  public void setArgs(List<VarReference> args) {
    if (args.size() != params().size()) {
      throw new RuntimeException("Unequal number of args and params");
    }

    this.args = args;
  }

  public abstract List<Type> generics();

  @Override
  public abstract List<Param> params();

  @Override
  public List<Type> actualParamTypes() {
    return args.stream().peek(Objects::requireNonNull).map(VarReference::type).toList();
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public void setArg(int pos, VarReference arg) {
    args.set(pos, arg);
  }

  public abstract Optional<Type> parent();

  public abstract String name();

  @Override
  public Optional<VarReference> returnValue() {
    return Optional.ofNullable(returnValue);
  }

  @Override
  public boolean isCallableStmt() {
    return true;
  }

  @Override
  public CallableStmt asCallableStmt() {
    return this;
  }

  public Optional<String> ofTrait() {
    throw new RuntimeException("Not implemented");
  }
  @Override
  public boolean consumes(VarReference var) {
//    return Streams.zip(params().stream(), args.stream(), Pair::with)
//        .filter(pair -> pair.getValue1().equals(var))
//        .anyMatch(pair -> !pair.getValue0().isByReference());
    if (var.type().isRef()) {
      var stmt = var.definedBy();
      if (stmt.isRefStmt()) {
        var referencedVar = var.definedBy().asRefStmt().arg();
        return args.contains(referencedVar);
      } else if (stmt.isTupleStmt() || stmt.isArrayStmt()) {
        throw new RuntimeException("Not implemented");
      } else {
        return false;
      }
    } else {
      return args.contains(var);
    }
  }

  @Override
  public boolean borrows(VarReference var) {
    if (var.type().isRef()) {
      return args.contains(var);
    } else {
      var referencedVars = args.stream().filter(a -> a.type().isRef())
          .map(VarReference::definedBy)
          .filter(Statement::isRefStmt)
          .map(s -> s.asRefStmt().arg())
          .toList();
      return referencedVars.contains(var);
    }
  }

  @Override
  public boolean mutates(VarReference var) {
    return Streams.zip(params().stream(), args.stream(), Pair::with)
        .filter(pair -> pair.getValue1().equals(var))
        .anyMatch(pair -> pair.getValue0().isByReference() && pair.getValue0().isMutable());
  }

  @Override
  public boolean uses(VarReference var) {
    return args.stream().anyMatch(a -> a.equals(var));
  }

  @Override
  public void replace(VarReference oldVar, VarReference newVar) {
    if (!args.contains(oldVar)) {
      throw new RuntimeException("There's something wrong");
    }

    /*var typeBinding = testCase.popTypeBindingsFor(oldVar);
    testCase.setTypeBindingsFor(newVar, typeBinding);*/

    args = args.stream().map(a -> {
      if (a.equals(oldVar)) {
        return newVar;
      } else {
        return a;
      }
    }).collect(Collectors.toCollection(ArrayList::new));
  }

  @Override
  public int position() {
    return testCase.stmtPosition(this).orElseThrow();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CallableStmt)) {
      return false;
    }
    CallableStmt that = (CallableStmt) o;
    return id.equals(that.id) && args.equals(that.args) && Objects.equals(returnValue,
        that.returnValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, args, returnValue);
  }
}
