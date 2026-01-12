package databasehandler

import (
	"database/sql"
	"fmt"
	"log"

	_ "github.com/lib/pq"
	"github.com/spf13/viper"

	"github.com/golang-migrate/migrate/v4"
	"github.com/golang-migrate/migrate/v4/database/postgres"
	_ "github.com/golang-migrate/migrate/v4/source/file"
)

func runMigrations(db *sql.DB, migrationsPath string) {
	driver, err := postgres.WithInstance(db, &postgres.Config{})
	if err != nil {
		log.Fatalf("Could not create postgres driver: %v", err)
	}

	m, err := migrate.NewWithDatabaseInstance(
		fmt.Sprintf("file://./%s", migrationsPath),
		"postgres",
		driver,
	)
	if err != nil {
		log.Fatalf("Could not initialize migration: %v", err)
	}

	err = m.Up()
	if err != nil && err != migrate.ErrNoChange {
		log.Fatalf("Could not run up migrations: %v", err)
	}

	log.Println("Migrations applied successfully")
}
func DbConnection() (*sql.DB, error) {

	dburl := fmt.Sprintf("postgres://%s:%s@%s:%d/%s?sslmode=disable",
		viper.GetString("docker.postgres.user"),
		viper.GetString("docker.postgres.password"),
		viper.GetString("docker.postgres.host"),
		viper.GetInt("docker.postgres.port"),
		viper.GetString("docker.postgres.db"),
	)
	db, err := sql.Open("postgres", dburl)
	if err != nil {
		return nil, err
	}
	runMigrations(db, "database/migrations")
	return db, nil
}
