@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"checkout::api", "finance::api", "inventory::api", "shared"}
)
package inv.dashboard;
