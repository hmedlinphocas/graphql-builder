package com.fleetpin.graphql.builder.inputgenerics;

import com.fleetpin.graphql.builder.annotations.Entity;
import com.fleetpin.graphql.builder.annotations.SchemaOption;

@Entity(SchemaOption.INPUT)
public class CatAnimalInput extends AnimalInput<Cat> {

}